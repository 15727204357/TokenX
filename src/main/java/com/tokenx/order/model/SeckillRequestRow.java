package com.tokenx.order.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("seckill_request")
public class SeckillRequestRow {
  @TableId(type = IdType.AUTO) private Long id;
  private byte[] requestId;
  private Long userId;
  private Long activityId;
  private Integer status;
  private Long orderId;
  private String errorCode;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  public Long getId(){return id;} public void setId(Long v){id=v;}
  public byte[] getRequestId(){return requestId;} public void setRequestId(byte[] v){requestId=v;}
  public Long getUserId(){return userId;} public void setUserId(Long v){userId=v;}
  public Long getActivityId(){return activityId;} public void setActivityId(Long v){activityId=v;}
  public Integer getStatus(){return status;} public void setStatus(Integer v){status=v;}
  public Long getOrderId(){return orderId;} public void setOrderId(Long v){orderId=v;}
  public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
  public LocalDateTime getCreatedAt(){return createdAt;} public void setCreatedAt(LocalDateTime v){createdAt=v;}
  public LocalDateTime getUpdatedAt(){return updatedAt;} public void setUpdatedAt(LocalDateTime v){updatedAt=v;}
}
