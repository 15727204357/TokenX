package com.tokenx.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tokenx.order.model.SeckillRequestRow;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

@Mapper
public interface SeckillRequestMapper extends BaseMapper<SeckillRequestRow> {

  @Select("SELECT id, request_id, user_id, activity_id, status, order_id, error_code, created_at, updated_at FROM seckill_request WHERE user_id=#{userId} AND request_id=#{requestId}")
  SeckillRequestRow find(@Param("userId") long userId, @Param("requestId") byte[] requestId);

  @Select("SELECT id, request_id, user_id, activity_id, status, order_id, error_code, created_at, updated_at FROM seckill_request WHERE request_id=#{requestId}")
  SeckillRequestRow findByRequestId(@Param("requestId") byte[] requestId);

  @Select("SELECT id, request_id, user_id, activity_id, status, order_id, error_code, created_at, updated_at FROM seckill_request WHERE activity_id=#{activityId} AND status IN (0,1,6)")
  java.util.List<SeckillRequestRow> findPendingByActivity(@Param("activityId") long activityId);

  @Select("SELECT id, request_id, user_id, activity_id, status, order_id, error_code, created_at, updated_at FROM seckill_request WHERE status IN (5,6) AND updated_at < #{before} ORDER BY id LIMIT #{limit}")
  java.util.List<SeckillRequestRow> findStaleReservations(@Param("before") LocalDateTime before, @Param("limit") int limit);

  @Select("SELECT COUNT(*) FROM seckill_request WHERE activity_id=#{activityId} AND status=5")
  long countUnknownByActivity(@Param("activityId") long activityId);

  @Update("UPDATE seckill_request SET status=#{status}, order_id=#{orderId}, error_code=#{errorCode}, updated_at=#{now} WHERE request_id=#{requestId} AND status IN (0,1,5,6)")
  int finish(@Param("requestId") byte[] requestId, @Param("status") int status, @Param("orderId") Long orderId, @Param("errorCode") String errorCode, @Param("now") LocalDateTime now);

  @Update("UPDATE seckill_request SET status=#{status}, updated_at=#{now} WHERE request_id=#{requestId} AND user_id=#{userId} AND status IN (0,1,5,6)")
  int updatePending(@Param("requestId") byte[] requestId,@Param("userId") long userId,@Param("status") int status,@Param("now") LocalDateTime now);

  @Update("UPDATE seckill_request SET status=0, updated_at=#{now} WHERE request_id=#{requestId} AND user_id=#{userId} AND status IN (5,6)")
  int markAcknowledged(@Param("requestId") byte[] requestId,@Param("userId") long userId,@Param("now") LocalDateTime now);

  @Update("UPDATE seckill_request SET updated_at=#{now} WHERE request_id=#{requestId} AND status IN (5,6) AND updated_at < #{before}")
  int claimStaleReservation(@Param("requestId") byte[] requestId,@Param("before") LocalDateTime before,@Param("now") LocalDateTime now);

  @Update("UPDATE seckill_request SET status=1, updated_at=#{now} WHERE request_id=#{requestId} AND status IN (0,5,6)")
  int markProcessing(@Param("requestId") byte[] requestId,@Param("now") LocalDateTime now);

  @Delete("DELETE FROM seckill_request WHERE request_id=#{requestId} AND user_id=#{userId} AND status=6")
  int deletePending(@Param("requestId") byte[] requestId,@Param("userId") long userId);
}
