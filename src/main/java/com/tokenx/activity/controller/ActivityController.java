package com.tokenx.activity.controller;

import com.tokenx.activity.dto.ActivityPublicResponse;
import com.tokenx.activity.service.ActivityService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public activity detail. Anonymous visitors may read it; only published activities are visible.
 *
 * <p>The security rule that opens this path is scoped to {@code GET} on purpose: F4 adds
 * {@code POST /activities/{id}/orders}, and that endpoint must never inherit anonymous access.
 */
@RestController
@RequestMapping("/api/v1/activities")
public class ActivityController {

  private final ActivityService activityService;

  public ActivityController(ActivityService activityService) {
    this.activityService = activityService;
  }

  @GetMapping("/{id}")
  public ActivityPublicResponse detail(@PathVariable @Positive(message = "id must be positive") long id) {
    return activityService.findPublishedById(id);
  }
}
