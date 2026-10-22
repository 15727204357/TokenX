package com.tokenx.order.dto;

import com.tokenx.order.model.SeckillRequestStatus;

/** Public state of an accepted asynchronous order request. */
public record OrderRequestResponse(String requestId, SeckillRequestStatus status, String orderNo, String errorCode) {}
