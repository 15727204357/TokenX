package com.tokenx.order.model;

/** Durable outcome for an asynchronously admitted seckill request. */
public enum SeckillRequestStatus {
  QUEUED,
  PROCESSING,
  ORDERED,
  REJECTED,
  FAILED,
  UNKNOWN,
  RESERVED
}
