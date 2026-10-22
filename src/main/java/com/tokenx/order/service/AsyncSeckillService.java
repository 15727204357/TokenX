package com.tokenx.order.service;

import tools.jackson.databind.ObjectMapper;
import com.tokenx.activity.service.ActivityService;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.model.SeckillOrderCommand;
import com.tokenx.order.config.SeckillProperties;
import com.tokenx.order.dto.OrderRequestResponse;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.model.PurchaseOrder;
import com.tokenx.order.model.SeckillRequestRow;
import com.tokenx.order.model.SeckillRequestStatus;
import com.tokenx.order.support.IdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Coordinates durable MySQL request intent, Redis admission, and Kafka broker acknowledgement. */
@Service
public class AsyncSeckillService {
  private static final Logger log = LoggerFactory.getLogger(AsyncSeckillService.class);

  private final SeckillProperties props;
  private final SeckillAdmissionGate gate;
  private final ActivityService activities;
  private final KafkaTemplate<Object, Object> kafka;
  private final MessagingProperties messaging;
  private final ObjectMapper json;
  private final SeckillRequestMapper requests;
  private final PurchaseOrderMapper orders;
  private final Clock clock;

  public AsyncSeckillService(
      SeckillProperties props,
      SeckillAdmissionGate gate,
      ActivityService activities,
      KafkaTemplate<Object, Object> kafka,
      MessagingProperties messaging,
      ObjectMapper json,
      SeckillRequestMapper requests,
      PurchaseOrderMapper orders,
      Clock clock) {
    this.props = props;
    this.gate = gate;
    this.activities = activities;
    this.kafka = kafka;
    this.messaging = messaging;
    this.json = json;
    this.requests = requests;
    this.orders = orders;
    this.clock = clock;
  }

  public OrderRequestResponse submit(long user, long activity, UUID request) {
    requireAsyncMode();
    byte[] requestBytes = IdempotencyKeys.toBytes(request);
    SeckillRequestRow existing;
    try {
      existing = requests.find(user, requestBytes);
    } catch (DataAccessException ex) {
      throw unavailable();
    }
    if (existing != null) {
      if (existing.getActivityId() != activity) {
        throw requestKeyConflict();
      }
      return replayExisting(existing, request);
    }

    Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
    activities.requireOrderable(activity, now);
    final long admission;
    try {
      admission = gate.reserve(activity, user, request);
    } catch (DataAccessException ex) {
      throw unavailable();
    }
    if (admission == -1 || admission == -3) {
      throw unavailable();
    }
    if (admission == -2) {
      throw new BusinessException(
          HttpStatus.CONFLICT,
          "ALREADY_PURCHASED",
          "user already has an active request for this activity");
    }
    if (admission == 0) {
      throw new BusinessException(
          HttpStatus.CONFLICT, "ACTIVITY_SOLD_OUT", "activity is sold out");
    }

    SeckillRequestRow row = new SeckillRequestRow();
    row.setRequestId(requestBytes);
    row.setUserId(user);
    row.setActivityId(activity);
    row.setStatus(6); // RESERVED: no broker ACK has been recorded yet.
    row.setCreatedAt(PurchaseOrder.toColumn(now));
    row.setUpdatedAt(row.getCreatedAt());
    try {
      requests.insert(row);
    } catch (DuplicateKeyException ex) {
      SeckillRequestRow winner = requests.find(user, requestBytes);
      if (winner == null || winner.getActivityId() != activity) {
        compensate(activity, user, request);
        throw requestKeyConflict();
      }
      return replayExisting(winner, request);
    } catch (DataAccessException ex) {
      compensate(activity, user, request);
      throw unavailable();
    }

    return publishReserved(row, request);
  }

  public OrderRequestResponse find(long user, UUID request) {
    final SeckillRequestRow row;
    try {
      row = requests.find(user, IdempotencyKeys.toBytes(request));
    } catch (DataAccessException ex) {
      throw unavailable();
    }
    if (row == null) {
      throw new BusinessException(
          HttpStatus.NOT_FOUND, "ORDER_REQUEST_NOT_FOUND", "order request not found");
    }
    return response(row, request);
  }

  private OrderRequestResponse replayExisting(SeckillRequestRow row, UUID request) {
    int status = row.getStatus();
    if (status == 2 || status == 3 || status == 4) {
      return response(row, request);
    }
    if (status == 5 || status == 6) {
      return publishReserved(row, request);
    }
    return response(row, request);
  }

  private OrderRequestResponse publishReserved(SeckillRequestRow row, UUID request) {
    SeckillOrderCommand command =
        new SeckillOrderCommand(
            1,
            request,
            row.getActivityId(),
            row.getUserId(),
            PurchaseOrder.toInstant(row.getCreatedAt()));
    final String payload;
    try {
      payload = json.writeValueAsString(command);
    } catch (RuntimeException ex) {
      deleteAndCompensate(row, request);
      throw unavailable();
    }

    final java.util.concurrent.Future<?> send;
    try {
      send =
          kafka.send(
              messaging.getSeckillOrdersTopic(), Long.toString(row.getActivityId()), payload);
    } catch (RuntimeException ex) {
      deleteAndCompensate(row, request);
      throw unavailable();
    }
    try {
      send.get(messaging.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException ex) {
      markUnknown(row, request);
      throw unavailable();
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      markUnknown(row, request);
      throw unavailable();
    } catch (ExecutionException ex) {
      if (isAmbiguous(ex.getCause())) {
        markUnknown(row, request);
      } else {
        deleteAndCompensate(row, request);
      }
      throw unavailable();
    }

    try {
      requests.markAcknowledged(
          IdempotencyKeys.toBytes(request), row.getUserId(), PurchaseOrder.toColumn(clock.instant()));
    } catch (DataAccessException ex) {
      // The Kafka ACK is sufficient for 202. The durable RESERVED row remains queryable and can
      // be retried with the same key after MySQL recovers.
      log.warn("seckill ACK state update deferred requestId={}", request);
    }
    try {
      if (!gate.queued(row.getActivityId(), row.getUserId(), request)) {
        gate.markRebuilding(row.getActivityId());
      }
    } catch (DataAccessException ex) {
      try {
        gate.markRebuilding(row.getActivityId());
      } catch (DataAccessException ignored) {
        // The request row and Kafka record remain authoritative; recovery can rebuild the gate.
      }
    }
    return new OrderRequestResponse(request.toString(), SeckillRequestStatus.QUEUED, null, null);
  }

  private OrderRequestResponse response(SeckillRequestRow row, UUID request) {
    SeckillRequestStatus status =
        row.getStatus() == 5 || row.getStatus() == 6
            ? SeckillRequestStatus.PROCESSING
            : SeckillRequestStatus.values()[row.getStatus()];
    var order = row.getOrderId() == null ? null : orders.selectById(row.getOrderId());
    return new OrderRequestResponse(
        request.toString(), status, order == null ? null : order.getOrderNo(), row.getErrorCode());
  }

  private void deleteAndCompensate(SeckillRequestRow row, UUID request) {
    try {
      int deleted = requests.deletePending(IdempotencyKeys.toBytes(request), row.getUserId());
      if (deleted == 1) {
        compensate(row.getActivityId(), row.getUserId(), request);
      } else {
        markUnknown(row, request);
      }
    } catch (DataAccessException ex) {
      log.error("seckill failed-send cleanup deferred requestId={}", request);
      markUnknown(row, request);
    }
  }

  private void markUnknown(SeckillRequestRow row, UUID request) {
    try {
      requests.updatePending(
          IdempotencyKeys.toBytes(request),
          row.getUserId(),
          5,
          PurchaseOrder.toColumn(clock.instant()));
    } catch (DataAccessException ex) {
      log.error("seckill ambiguous-send state update deferred requestId={}", request);
    }
    try {
      gate.unknown(row.getActivityId(), row.getUserId(), request);
      gate.markRebuilding(row.getActivityId());
    } catch (DataAccessException ex) {
      // A missing Redis gate also fails closed; the admin rebuild path records REBUILDING.
    }
  }

  private void compensate(long activity, long user, UUID request) {
    try {
      gate.compensate(activity, user, request);
    } catch (DataAccessException ex) {
      try {
        gate.markRebuilding(activity);
      } catch (DataAccessException ignored) {
        // Redis is not the source of order or inventory truth.
      }
    }
  }

  private void requireAsyncMode() {
    if (props.getMode() != SeckillProperties.Mode.ASYNC) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "NOT_FOUND", "resource not found");
    }
  }

  private static BusinessException requestKeyConflict() {
    return new BusinessException(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "request key was already used for another request");
  }

  private static BusinessException unavailable() {
    return new BusinessException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "SECKILL_ADMISSION_UNAVAILABLE",
        "order request could not be accepted; retry with the same Idempotency-Key");
  }

  private static boolean isAmbiguous(Throwable cause) {
    for (Throwable current = cause; current != null; current = current.getCause()) {
      if (current instanceof TimeoutException
          || current instanceof org.apache.kafka.common.errors.TimeoutException
          || current instanceof RetriableException) {
        return true;
      }
    }
    return false;
  }
}
