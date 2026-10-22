package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.tokenx.order.service.SeckillAdmissionGate;
import com.tokenx.support.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Uses only unique activity-scoped keys; never flushes or scans the shared Redis. */
@SpringBootTest
class SeckillAdmissionGateIntegrationTest extends AbstractIntegrationTest {
  @Autowired private SeckillAdmissionGate gate;
  @Autowired private StringRedisTemplate redis;
  private final List<Long> activityIds=new ArrayList<>();

  @AfterEach void cleanExactKeys(){for(long id:activityIds){String tag="tokenx:seckill:{"+id+"}";redis.delete(List.of(tag+":gate",tag+":users",tag+":requests"));}}

  @Test void concurrentAdmissionNeverMakesQuotaNegativeAndCompensationIsRepeatable() throws Exception {
    long activity=Math.abs(UUID.randomUUID().getMostSignificantBits());activityIds.add(activity);
    gate.initialize(activity,5,600);
    try(var pool=Executors.newFixedThreadPool(16)){
      var jobs=new ArrayList<java.util.concurrent.Future<Long>>();
      for(int i=0;i<40;i++){long user=i+1;UUID key=UUID.randomUUID();jobs.add(pool.submit(()->gate.reserve(activity,user,key)));}
      long accepted=0;for(var job:jobs)if(job.get()==1)accepted++;
      assertThat(accepted).isEqualTo(5);
    }
    String gateKey="tokenx:seckill:{"+activity+"}:gate";
    assertThat(Long.parseLong(redis.opsForHash().get(gateKey,"remaining").toString())).isZero();
    UUID duplicate=UUID.randomUUID();
    assertThat(gate.reserve(activity,1000,duplicate)).isEqualTo(0);
    long other=Math.abs(UUID.randomUUID().getMostSignificantBits());activityIds.add(other);gate.initialize(other,1,600);
    UUID same=UUID.randomUUID();
    assertThat(gate.reserve(other,88,same)).isEqualTo(1);
    assertThat(gate.reserve(other,88,same)).isEqualTo(2);
    assertThat(gate.reserve(other,88,UUID.randomUUID())).isEqualTo(-2);
    gate.compensate(other,88,same);gate.compensate(other,88,same);
    assertThat(Long.parseLong(redis.opsForHash().get("tokenx:seckill:{"+other+"}:gate","remaining").toString())).isEqualTo(1);
    assertThat(gate.reserve(other,88,same)).isEqualTo(1);
    assertThat(Long.parseLong(redis.opsForHash().get("tokenx:seckill:{"+other+"}:gate","remaining").toString())).isZero();
  }

  @Test void lostAdmissionKeysFailClosedAndMarkGateRebuilding() {
    long activity=Math.abs(UUID.randomUUID().getMostSignificantBits());activityIds.add(activity);
    gate.initialize(activity,2,600);
    String tag="tokenx:seckill:{"+activity+"}";
    redis.delete(List.of(tag+":users"));

    assertThat(gate.reserve(activity,42,UUID.randomUUID())).isEqualTo(-1);
    assertThat(redis.opsForHash().get(tag+":gate","state")).isEqualTo("REBUILDING");
  }

  @Test void ambiguousSendKeepsQuotaReservedButReleasesInFlightCounter() {
    long activity=Math.abs(UUID.randomUUID().getMostSignificantBits());activityIds.add(activity);
    gate.initialize(activity,1,600);
    UUID request=UUID.randomUUID();
    assertThat(gate.reserve(activity,77,request)).isEqualTo(1);
    gate.unknown(activity,77,request);
    assertThat(gate.inflight(activity)).isZero();
    assertThat(gate.status(activity,77,request)).isEqualTo("UNKNOWN");
    assertThat(Long.parseLong(redis.opsForHash().get("tokenx:seckill:{"+activity+"}:gate","remaining").toString())).isZero();
    assertThat(gate.queued(activity,77,request)).isTrue();
    assertThat(gate.status(activity,77,request)).isEqualTo("QUEUED");
  }
}
