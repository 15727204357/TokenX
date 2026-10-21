package com.tokenx.activity.controller;

import com.tokenx.activity.dto.ActivityAdminResponse;
import com.tokenx.activity.dto.CreateActivityRequest;
import com.tokenx.activity.dto.UpdateActivityStatusRequest;
import com.tokenx.activity.model.ActivityStatus;
import com.tokenx.activity.service.ActivityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin activity management. Access is enforced by the security filter chain ({@code ROLE_ADMIN});
 * the controller validates the payload and maps the result to a status code, nothing else.
 */
@RestController
@RequestMapping("/api/v1/admin/activities")
public class AdminActivityController {

  private final ActivityService activityService;

  public AdminActivityController(ActivityService activityService) {
    this.activityService = activityService;
  }

  /** Creates a draft activity with its initial stock; publishing is a separate, explicit step. */
  @PostMapping
  public ResponseEntity<ActivityAdminResponse> create(
      @Valid @RequestBody CreateActivityRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(activityService.create(request));
  }

  /** Publishes or withdraws an activity. Repeating the current status is a success. */
  @PatchMapping("/{id}/status")
  public ActivityAdminResponse changeStatus(
      @PathVariable @Positive(message = "id must be positive") long id,
      @Valid @RequestBody UpdateActivityStatusRequest request) {
    return activityService.changeStatus(id, ActivityStatus.valueOf(request.status()));
  }
}
