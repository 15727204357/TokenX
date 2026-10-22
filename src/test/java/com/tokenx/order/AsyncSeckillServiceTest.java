package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tokenx.activity.dto.ActivityOrderSnapshot;
import com.tokenx.activity.service.ActivityService;
import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.order.config.SeckillProperties;
import com.tokenx.order.mapper.PurchaseOrderMapper;
import com.tokenx.order.mapper.SeckillRequestMapper;
import com.tokenx.order.service.AsyncSeckillService;
import com.tokenx.order.service.SeckillAdmissionGate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

class AsyncSeckillServiceTest {
  @Test void sendsFailureCompensatesAndReturns503() throws Exception {
    var gate=mock(SeckillAdmissionGate.class);var activities=mock(ActivityService.class);var kafka=mock(KafkaTemplate.class);var rows=mock(SeckillRequestMapper.class);var orders=mock(PurchaseOrderMapper.class);
    when(gate.reserve(7,9,KEY)).thenReturn(1L);when(gate.status(7,9,KEY)).thenReturn("RESERVED");when(rows.find(anyLong(),any(byte[].class))).thenReturn(null);
    when(rows.deletePending(any(byte[].class),anyLong())).thenReturn(1);
    CompletableFuture<SendResult<Object,Object>> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("broker unavailable"));
    when(kafka.send(anyString(),anyString(),anyString())).thenReturn(failed);
    var props=new SeckillProperties();props.setMode(SeckillProperties.Mode.ASYNC);
    var service=new AsyncSeckillService(props,gate,activities,kafka,new MessagingProperties(),new ObjectMapper(),rows,orders,Clock.fixed(Instant.parse("2026-10-22T10:00:00Z"),ZoneOffset.UTC));
    assertThatThrownBy(()->service.submit(9,7,KEY)).isInstanceOf(com.tokenx.infrastructure.error.BusinessException.class)
        .satisfies(e->assertThat(((com.tokenx.infrastructure.error.BusinessException)e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    verify(gate).compensate(7,9,KEY);verify(rows).deletePending(any(byte[].class),org.mockito.ArgumentMatchers.eq(9L));
  }
  @Test void returnsQueuedOnlyAfterFutureAcknowledges() throws Exception {
    var gate=mock(SeckillAdmissionGate.class);var activities=mock(ActivityService.class);var kafka=mock(KafkaTemplate.class);var rows=mock(SeckillRequestMapper.class);var orders=mock(PurchaseOrderMapper.class);
    when(gate.reserve(7,9,KEY)).thenReturn(1L);when(gate.status(7,9,KEY)).thenReturn("RESERVED");when(rows.find(anyLong(),any(byte[].class))).thenReturn(null);
    CompletableFuture<SendResult<Object,Object>> pending=new CompletableFuture<>();
    CountDownLatch sendStarted=new CountDownLatch(1);
    when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(invocation->{sendStarted.countDown();return pending;});
    var props=new SeckillProperties();props.setMode(SeckillProperties.Mode.ASYNC);
    var service=new AsyncSeckillService(props,gate,activities,kafka,new MessagingProperties(),new ObjectMapper(),rows,orders,Clock.systemUTC());
    try(var pool=Executors.newSingleThreadExecutor()){
      var result=pool.submit(()->service.submit(9,7,KEY));
      assertThat(sendStarted.await(1,TimeUnit.SECONDS)).isTrue();
      assertThat(result.isDone()).isFalse();
      pending.complete(null);
      assertThat(result.get(1,TimeUnit.SECONDS).status()).isEqualTo(com.tokenx.order.model.SeckillRequestStatus.QUEUED);
    }
    verify(gate).queued(7,9,KEY);
  }

  @Test void sameIdempotencyKeyCanRetryAfterDefiniteBrokerFailure() throws Exception {
    var gate=mock(SeckillAdmissionGate.class);var activities=mock(ActivityService.class);var kafka=mock(KafkaTemplate.class);var rows=mock(SeckillRequestMapper.class);var orders=mock(PurchaseOrderMapper.class);
    when(gate.reserve(7,9,KEY)).thenReturn(1L,1L);when(gate.status(7,9,KEY)).thenReturn("RESERVED","RESERVED");when(rows.find(anyLong(),any(byte[].class))).thenReturn(null);
    when(rows.deletePending(any(byte[].class),anyLong())).thenReturn(1);
    CompletableFuture<SendResult<Object,Object>> failed=new CompletableFuture<>();failed.completeExceptionally(new IllegalStateException("broker unavailable"));
    when(kafka.send(anyString(),anyString(),anyString())).thenReturn(failed,CompletableFuture.completedFuture(null));
    var props=new SeckillProperties();props.setMode(SeckillProperties.Mode.ASYNC);
    var service=new AsyncSeckillService(props,gate,activities,kafka,new MessagingProperties(),new ObjectMapper(),rows,orders,Clock.systemUTC());

    assertThatThrownBy(()->service.submit(9,7,KEY)).isInstanceOf(com.tokenx.infrastructure.error.BusinessException.class)
        .satisfies(e->assertThat(((com.tokenx.infrastructure.error.BusinessException)e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(service.submit(9,7,KEY).status()).isEqualTo(com.tokenx.order.model.SeckillRequestStatus.QUEUED);
    verify(gate,org.mockito.Mockito.times(2)).reserve(7,9,KEY);
  }

  @Test void unknownAndReservedRowsAreReportedAsProcessingAndFailuresStayDistinct() {
    var gate=mock(SeckillAdmissionGate.class);var activities=mock(ActivityService.class);var kafka=mock(KafkaTemplate.class);var rows=mock(SeckillRequestMapper.class);var orders=mock(PurchaseOrderMapper.class);
    var props=new SeckillProperties();props.setMode(SeckillProperties.Mode.ASYNC);
    var service=new AsyncSeckillService(props,gate,activities,kafka,new MessagingProperties(),new ObjectMapper(),rows,orders,Clock.systemUTC());
    var row=new com.tokenx.order.model.SeckillRequestRow();row.setRequestId(com.tokenx.order.support.IdempotencyKeys.toBytes(KEY));row.setUserId(9L);row.setActivityId(7L);row.setStatus(5);
    when(rows.find(anyLong(),any(byte[].class))).thenReturn(row);
    assertThat(service.find(9,KEY).status()).isEqualTo(com.tokenx.order.model.SeckillRequestStatus.PROCESSING);
    row.setStatus(6);
    assertThat(service.find(9,KEY).status()).isEqualTo(com.tokenx.order.model.SeckillRequestStatus.PROCESSING);
    row.setStatus(4);row.setErrorCode("COMMAND_UNPROCESSABLE");
    assertThat(service.find(9,KEY).status()).isEqualTo(com.tokenx.order.model.SeckillRequestStatus.FAILED);
    assertThat(service.find(9,KEY).errorCode()).isEqualTo("COMMAND_UNPROCESSABLE");
  }
  private static final UUID KEY=UUID.fromString("e730d11c-f9cd-4a82-b644-e97b6cb180a1");
}
