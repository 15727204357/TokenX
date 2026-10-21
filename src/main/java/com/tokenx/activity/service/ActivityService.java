package com.tokenx.activity.service;

import com.tokenx.activity.dto.ActivityAdminResponse;
import com.tokenx.activity.dto.ActivityPublicResponse;
import com.tokenx.activity.dto.CreateActivityRequest;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.mapper.SeckillActivityMapper;
import com.tokenx.activity.model.ActivityInventory;
import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.activity.model.PublishedActivityRow;
import com.tokenx.activity.model.SeckillActivity;
import com.tokenx.catalog.dto.PackageSnapshot;
import com.tokenx.catalog.service.CatalogService;
import com.tokenx.infrastructure.error.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Activity configuration and public reading.
 *
 * <p>Three rules shape this class:
 *
 * <ul>
 *   <li><b>Two different state machines.</b> {@code status} is the configuration state an admin
 *       moves through {@code DRAFT -> PUBLISHED -> OFFLINE}; "upcoming / active / ended" is derived
 *       from the injected {@link Clock} on every read. Nothing writes a run-time state to a column.
 *   <li><b>The server owns every value that costs money.</b> Price, credit snapshot and stock all
 *       come from the request only where the admin is allowed to choose; the credit snapshot is read
 *       from the package, and stock is initialised to {@code N / N / 0} by this class.
 *   <li><b>Low-frequency writes are serialised by a single row lock.</b> Status changes take
 *       {@code FOR UPDATE} on the activity primary key, so two concurrent publishes cannot both read
 *       a stale state.
 * </ul>
 *
 * <p>This task intentionally stops before ordering: no endpoint here touches stock beyond creating
 * the initial row.
 */
@Service
public class ActivityService {

  static final String ACTIVITY_NOT_FOUND = "ACTIVITY_NOT_FOUND";
  static final String ACTIVITY_STATE_CONFLICT = "ACTIVITY_STATE_CONFLICT";
  static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  /**
   * The range MySQL can store in {@code DATETIME}. A window outside it is rejected as a bad request
   * instead of being allowed to fail as a server error inside the insert.
   */
  private static final Instant DATETIME_MIN = Instant.parse("1000-01-01T00:00:00Z");

  private static final Instant DATETIME_MAX = Instant.parse("9999-12-31T23:59:59.999Z");

  private final SeckillActivityMapper activityMapper;
  private final ActivityInventoryMapper inventoryMapper;
  private final CatalogService catalogService;
  private final Clock clock;

  public ActivityService(
      SeckillActivityMapper activityMapper,
      ActivityInventoryMapper inventoryMapper,
      CatalogService catalogService,
      Clock clock) {
    this.activityMapper = activityMapper;
    this.inventoryMapper = inventoryMapper;
    this.catalogService = catalogService;
    this.clock = clock;
  }

  /**
   * Creates a draft activity and its stock row in one transaction.
   *
   * <p>The two inserts are all-or-nothing: if the stock insert fails, the activity insert is rolled
   * back with it, so the database can never hold an activity without stock (there is no foreign key
   * to catch that for us).
   *
   * <p>The credit amount is read from the package by the server. A value sent by the client is not
   * part of {@link CreateActivityRequest} and therefore cannot be honoured.
   */
  @Transactional
  public ActivityAdminResponse create(CreateActivityRequest request) {
    Instant now = now();
    // Validate exactly the values that will be stored: both are truncated to MySQL's millisecond
    // precision first, so a window that is legal here is legal in the database too.
    Instant startAt = truncate(request.startAt());
    Instant endAt = truncate(request.endAt());
    requireValidWindow(startAt, endAt, now);

    PackageSnapshot tokenPackage =
        catalogService.requireOnSalePackageForActivity(request.packageId());

    SeckillActivity activity = new SeckillActivity();
    activity.setPackageId(tokenPackage.id());
    activity.setActivityPriceCent(request.activityPriceCent());
    activity.setCreditAmountSnapshot(tokenPackage.creditAmount());
    activity.setWindow(startAt, endAt);
    activity.setStatus(ActivityStatus.DRAFT);
    activityMapper.insert(activity);

    ActivityInventory inventory =
        ActivityInventory.initial(activity.getId(), request.totalStock().intValue());
    inventoryMapper.insert(inventory);

    return ActivityAdminResponse.from(activity, inventory, now);
  }

  /**
   * Publishes or withdraws an activity.
   *
   * <p>The row is locked before its status is read, so concurrent status changes of the same
   * activity are serialised by InnoDB and the waiting transaction reads the committed value instead
   * of its own older snapshot. Only that one primary key is locked: other activities are unaffected,
   * and no version column or distributed lock is involved.
   *
   * <p>Repeating the current status returns it without executing any {@code UPDATE}. Everything else
   * that is not one of the two legal edges is a {@code 409}: {@code OFFLINE} is terminal and nothing
   * returns to {@code DRAFT}.
   *
   * <p>Publishing re-reads the package: it must still be on sale, the window must not have ended
   * yet, and the credit snapshot is refreshed from the package so the published snapshot always
   * comes from a server fact rather than from whatever was true at creation time. Withdrawing only
   * changes the configuration state — the activity, its stock and its snapshot all stay.
   */
  @Transactional
  public ActivityAdminResponse changeStatus(long id, ActivityStatus target) {
    Instant now = now();
    SeckillActivity activity = activityMapper.selectByIdForUpdate(id);
    if (activity == null) {
      throw activityNotFound();
    }
    if (activity.getStatus() == target) {
      return response(activity, now);
    }
    if (!activity.getStatus().canTransitionTo(target)) {
      throw stateConflict(
          "activity cannot move from " + activity.getStatus() + " to " + target);
    }

    if (target == ActivityStatus.PUBLISHED) {
      if (!now.isBefore(activity.getEndInstant())) {
        throw stateConflict("activity has already ended and cannot be published");
      }
      PackageSnapshot tokenPackage =
          catalogService.requireOnSalePackageForActivity(activity.getPackageId());
      activityMapper.publish(id, tokenPackage.creditAmount());
      activity.setCreditAmountSnapshot(tokenPackage.creditAmount());
    } else {
      activityMapper.updateStatus(id, target);
    }
    activity.setStatus(target);
    return response(activity, now);
  }

  /**
   * Public detail of a published activity.
   *
   * <p>A missing activity, a draft and a withdrawn one all produce the same 404: the query filters on
   * {@code status = PUBLISHED}, so the three cases are indistinguishable from outside and the
   * endpoint cannot be used to discover unpublished activities.
   */
  public ActivityPublicResponse findPublishedById(long id) {
    PublishedActivityRow row = activityMapper.selectPublishedDetail(id);
    if (row == null) {
      throw activityNotFound();
    }
    return ActivityPublicResponse.from(row, now());
  }

  private ActivityAdminResponse response(SeckillActivity activity, Instant now) {
    ActivityInventory inventory = inventoryMapper.selectById(activity.getId());
    return ActivityAdminResponse.from(activity, inventory, now);
  }

  /** Current time at the precision the database stores, so comparisons cannot drift. */
  private Instant now() {
    return truncate(clock.instant());
  }

  private static Instant truncate(Instant instant) {
    return instant.truncatedTo(ChronoUnit.MILLIS);
  }

  private static void requireValidWindow(Instant startAt, Instant endAt, Instant now) {
    if (!startAt.isBefore(endAt)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "startAt must be before endAt");
    }
    if (!endAt.isAfter(now)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "endAt must be in the future");
    }
    if (startAt.isBefore(DATETIME_MIN) || endAt.isAfter(DATETIME_MAX)) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          VALIDATION_FAILED,
          "activity window must stay between years 1000 and 9999");
    }
  }

  private static BusinessException activityNotFound() {
    return new BusinessException(
        HttpStatus.NOT_FOUND, ACTIVITY_NOT_FOUND, "activity not found");
  }

  private static BusinessException stateConflict(String message) {
    return new BusinessException(HttpStatus.CONFLICT, ACTIVITY_STATE_CONFLICT, message);
  }
}
