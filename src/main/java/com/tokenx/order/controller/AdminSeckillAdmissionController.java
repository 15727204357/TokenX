package com.tokenx.order.controller;

import com.tokenx.order.service.SeckillAdmissionRecoveryService;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrative recovery endpoint; operators must quiesce admission before calling it. */
@RestController
@RequestMapping("/api/v1/admin/activities")
public class AdminSeckillAdmissionController {
  private final SeckillAdmissionRecoveryService recovery;
  public AdminSeckillAdmissionController(SeckillAdmissionRecoveryService recovery){this.recovery=recovery;}
  @PostMapping("/{activityId}/admission/rebuild")
  public void rebuild(@PathVariable @Positive long activityId){recovery.rebuild(activityId);}
}
