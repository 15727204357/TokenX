package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.dao.DataAccessResourceFailureException;

/** Real MySQL + Redis + Kafka acceptance path on dedicated test topics. */
@SpringBootTest(properties={
  "spring.kafka.bootstrap-servers=${TOKENX_TEST_KAFKA_BOOTSTRAP_SERVERS:127.0.0.1:19092}",
  "spring.kafka.listener.auto-startup=true",
  "tokenx.messaging.kafka-enabled=true",
  "tokenx.messaging.listener-enabled=true",
  "tokenx.messaging.seckill-listener-enabled=true",
  "tokenx.messaging.outbox-enabled=false",
  "tokenx.messaging.order-events-topic=tokenx.test.f9.events.v1",
  "tokenx.messaging.order-events-dlt-topic=tokenx.test.f9.events.dlt.v1",
  "tokenx.messaging.consumer-group-id=tokenx-test-f9-events",
  "tokenx.messaging.topic-partitions=3",
  "tokenx.messaging.seckill-topic-partitions=3",
  "tokenx.messaging.seckill-listener-concurrency=3",
  "tokenx.messaging.topic-replication-factor=1",
  "tokenx.messaging.listener-retry-delay=20ms",
  "tokenx.messaging.listener-retry-count=1",
  "tokenx.seckill.mode=ASYNC",
  "tokenx.seckill.admission-ttl=10m"
})
@AutoConfigureMockMvc
class AsyncSeckillKafkaIntegrationTest extends AbstractOrderApiTest {
  private static final String TEST_RUN_ID=UUID.randomUUID().toString().replace("-","");

  @DynamicPropertySource
  static void uniqueKafkaResources(DynamicPropertyRegistry properties){
    properties.add("tokenx.messaging.seckill-orders-topic",()->"tokenx.test.f9."+TEST_RUN_ID+".commands.v1");
    properties.add("tokenx.messaging.seckill-orders-dlt-topic",()->"tokenx.test.f9."+TEST_RUN_ID+".commands.dlt.v1");
    properties.add("tokenx.messaging.seckill-consumer-group-id",()->"tokenx-test-f9-"+TEST_RUN_ID);
  }

  @org.springframework.beans.factory.annotation.Autowired private StringRedisTemplate redis;
  @org.springframework.beans.factory.annotation.Autowired private KafkaListenerEndpointRegistry registry;
  @org.springframework.beans.factory.annotation.Autowired private KafkaTemplate<Object,Object> kafka;
  @org.springframework.beans.factory.annotation.Autowired private com.tokenx.messaging.config.MessagingProperties messaging;
  @MockitoSpyBean private com.tokenx.order.service.SeckillOrderCommandProcessor processor;
  private final java.util.Set<Long> gateActivities=new java.util.HashSet<>();
  @AfterEach void cleanGateKeys(){
    var consumer=registry.getListenerContainer("seckillOrderCommandsListener");
    if(consumer!=null&&!consumer.isRunning())consumer.start();
    for(long id:gateActivities){String tag="tokenx:seckill:{"+id+"}";redis.delete(List.of(tag+":gate",tag+":users",tag+":requests"));}
  }

  @Test void brokerAckAcceptsAndWorkerEventuallyCreatesExactlyOneOrder() throws Exception {
    String admin=adminToken(); Buyer buyer=registerBuyer(); long activity=openActivity(admin,799L,3L);gateActivities.add(activity);
    String keyTag="tokenx:seckill:{"+activity+"}";
    redis.delete(List.of(keyTag+":gate",keyTag+":users",keyTag+":requests"));
    assertThat(gateState(activity)).isNull();
    mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",UUID.randomUUID().toString()))
        .andExpect(status().isServiceUnavailable());
    assertThat(gateState(activity)).isEqualTo("REBUILDING");
    mockMvc.perform(post("/api/v1/admin/activities/{id}/admission/rebuild",activity)
        .header("Authorization","Bearer "+admin)).andExpect(status().isOk());
    var consumer=registry.getListenerContainer("seckillOrderCommandsListener");
    consumer.stop();
    UUID key=UUID.randomUUID();
    MvcResult accepted=mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key.toString()))
        .andExpect(status().isAccepted()).andReturn();
    assertThat(objectMapper.readTree(body(accepted)).get("status").asText()).isEqualTo("QUEUED");
    MvcResult acceptedState=mockMvc.perform(get("/api/v1/order-requests/{id}",key)
        .header("Authorization","Bearer "+buyer.token())).andReturn();
    var acceptedJson=objectMapper.readTree(body(acceptedState));
    assertThat(acceptedJson.get("status").asText()).isEqualTo("QUEUED");
    assertThat(acceptedJson.get("orderNo").isNull()).isTrue();
    mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key.toString()))
        .andExpect(status().isAccepted());
    assertThat(storedOrders(activity)).isEmpty();
    redis.delete(List.of(keyTag+":gate",keyTag+":users",keyTag+":requests"));
    mockMvc.perform(post("/api/v1/admin/activities/{id}/admission/rebuild",activity)
        .header("Authorization","Bearer "+admin)).andExpect(status().isOk());
    assertThat(redis.opsForHash().get(keyTag+":gate","remaining").toString()).isEqualTo("2");
    mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",UUID.randomUUID().toString()))
        .andExpect(status().isConflict());
    consumer.start();
    kafka.send(messaging.getSeckillOrdersTopic(),Long.toString(activity),objectMapper.writeValueAsString(
        new com.tokenx.messaging.model.SeckillOrderCommand(1,key,activity,buyer.id(),java.time.Instant.now())))
        .get(3,java.util.concurrent.TimeUnit.SECONDS);
    mockMvc.perform(post("/api/v1/activities/{id}/orders",activity).header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",UUID.randomUUID().toString())).andExpect(status().isNotFound());
    MvcResult finalState=null;long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    while(System.nanoTime()<deadline){
      finalState=mockMvc.perform(get("/api/v1/order-requests/{id}",key).header("Authorization","Bearer "+buyer.token())).andReturn();
      if(objectMapper.readTree(body(finalState)).get("status").asText().equals("ORDERED"))break;
      Thread.sleep(50);
    }
    assertThat(finalState).isNotNull();
    var response=objectMapper.readTree(body(finalState));
    assertThat(response.get("status").asText()).isEqualTo("ORDERED");
    assertThat(response.get("orderNo").asText()).hasSize(26);
    assertThat(storedOrders(activity)).hasSize(1);
    assertThat(storedInventory(activity).getAvailableStock()).isEqualTo(2);
    MvcResult replay=mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key.toString()))
        .andExpect(status().isAccepted()).andReturn();
    var replayJson=objectMapper.readTree(body(replay));
    assertThat(replayJson.get("status").asText()).isEqualTo("ORDERED");
    assertThat(replayJson.get("orderNo").asText()).isEqualTo(response.get("orderNo").asText());
    assertThat(storedOrders(activity)).hasSize(1);
    Buyer stranger=registerBuyer();
    mockMvc.perform(get("/api/v1/order-requests/{id}",key)
        .header("Authorization","Bearer "+stranger.token())).andExpect(status().isNotFound());
  }

  @Test void mysqlWorkerFailureDoesNotAdvanceOffsetAndRequestConvergesAfterRetry() throws Exception {
    String admin=adminToken(); Buyer buyer=registerBuyer(); long activity=openActivity(admin,799L,2L);
    gateActivities.add(activity);
    java.util.concurrent.atomic.AtomicBoolean first=new java.util.concurrent.atomic.AtomicBoolean(true);
    doAnswer(invocation->{
      if(first.compareAndSet(true,false))throw new DataAccessResourceFailureException("controlled transient MySQL failure");
      return invocation.callRealMethod();
    }).when(processor).process(any(com.tokenx.messaging.model.SeckillOrderCommand.class));
    UUID key=UUID.randomUUID();
    mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key.toString()))
        .andExpect(status().isAccepted());

    MvcResult finalState=awaitRequestState(buyer.token(),key,"ORDERED");
    assertThat(objectMapper.readTree(body(finalState)).get("status").asText()).isEqualTo("ORDERED");
    assertThat(storedOrders(activity)).hasSize(1);
    assertThat(storedInventory(activity).getAvailableStock()).isEqualTo(1);
    verify(processor,atLeast(2)).process(any(com.tokenx.messaging.model.SeckillOrderCommand.class));
  }

  @Test void queryReportsBusinessRejectionInsteadOfClaimingAnOrder() throws Exception {
    String admin=adminToken(); Buyer buyer=registerBuyer(); long activity=openActivity(admin,799L,1L);gateActivities.add(activity);
    var consumer=registry.getListenerContainer("seckillOrderCommandsListener");consumer.stop();
    UUID key=UUID.randomUUID();
    mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
        .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key.toString()))
        .andExpect(status().isAccepted());
    changeStatus(admin,activity,"OFFLINE").andExpect(status().isOk());
    consumer.start();

    MvcResult result=awaitRequestState(buyer.token(),key,"REJECTED");
    var response=objectMapper.readTree(body(result));
    assertThat(response.get("status").asText()).isEqualTo("REJECTED");
    assertThat(response.get("errorCode").asText()).isEqualTo("ACTIVITY_NOT_ACTIVE");
    assertThat(response.get("orderNo").isNull()).isTrue();
    assertThat(storedOrders(activity)).isEmpty();
  }

  @Test void concurrentAsyncAdmissionsNeverOverbookMySqlInventory() throws Exception {
    String admin=adminToken(); long activity=openActivity(admin,799L,5L);gateActivities.add(activity);
    java.util.List<Buyer> buyers=new java.util.ArrayList<>();
    for(int i=0;i<12;i++)buyers.add(registerBuyer());
    var start=new java.util.concurrent.CountDownLatch(1);
    try(var pool=java.util.concurrent.Executors.newFixedThreadPool(buyers.size())){
      var futures=new java.util.ArrayList<java.util.concurrent.Future<MvcResult>>();
      for(Buyer buyer:buyers){
        String key=UUID.randomUUID().toString();
        futures.add(pool.submit(()->{
          start.await();
          return mockMvc.perform(post("/api/v1/activities/{id}/order-requests",activity)
              .header("Authorization","Bearer "+buyer.token()).header("Idempotency-Key",key)).andReturn();
        }));
      }
      start.countDown();
      var accepted=new java.util.ArrayList<java.util.Map.Entry<Buyer,UUID>>();
      for(int i=0;i<futures.size();i++){
        MvcResult result=futures.get(i).get(10,java.util.concurrent.TimeUnit.SECONDS);
        int http=result.getResponse().getStatus();
        if(http==202){
          accepted.add(java.util.Map.entry(buyers.get(i),UUID.fromString(result.getRequest().getHeader("Idempotency-Key"))));
        }else{
          assertThat(http).isEqualTo(409);
          assertThat(errorCodeOf(result)).isEqualTo("ACTIVITY_SOLD_OUT");
        }
      }
      assertThat(accepted).hasSize(5);
      for(var entry:accepted){
        MvcResult state=awaitRequestState(entry.getKey().token(),entry.getValue(),"ORDERED");
        assertThat(objectMapper.readTree(body(state)).get("status").asText()).isEqualTo("ORDERED");
      }
    }
    assertThat(storedOrders(activity)).hasSize(5);
    assertThat(storedInventory(activity).getAvailableStock()).isZero();
    assertThat(storedInventory(activity).getReservedStock()).isEqualTo(5);
  }

  @Test void activityKeyKeepsSameActivityOrderedAndAllowsDistinctPartitions() throws Exception {
    String admin=adminToken();
    var listener=registry.getListenerContainer("seckillOrderCommandsListener");
    listener.stop();
    java.util.List<Integer> partitions=new java.util.ArrayList<>();
    java.util.List<Long> activities=new java.util.ArrayList<>();
    java.util.List<UUID> requests=new java.util.ArrayList<>();
    java.util.List<Buyer> buyers=new java.util.ArrayList<>();
    try {
    for(int attempt=0;attempt<10&&(partitions.isEmpty()||partitions.stream().allMatch(p->p.equals(partitions.get(0))));attempt++){
      Buyer buyer=registerBuyer(); long activity=openActivity(admin,799L,2L);gateActivities.add(activity);
      buyers.add(buyer);
      UUID request=UUID.randomUUID();requests.add(request);activities.add(activity);
      var row=new com.tokenx.order.model.SeckillRequestRow();
      row.setRequestId(com.tokenx.order.support.IdempotencyKeys.toBytes(request));row.setUserId(buyer.id());row.setActivityId(activity);
      row.setStatus(6);row.setCreatedAt(com.tokenx.order.model.PurchaseOrder.toColumn(java.time.Instant.now()));row.setUpdatedAt(row.getCreatedAt());
      seckillRequestMapper.insert(row);
      var command=new com.tokenx.messaging.model.SeckillOrderCommand(1,request,activity,buyer.id(),java.time.Instant.now());
      String payload=objectMapper.writeValueAsString(command);
      var first=kafka.send(messaging.getSeckillOrdersTopic(),Long.toString(activity),payload).get(3,java.util.concurrent.TimeUnit.SECONDS);
      var second=kafka.send(messaging.getSeckillOrdersTopic(),Long.toString(activity),payload).get(3,java.util.concurrent.TimeUnit.SECONDS);
      assertThat(second.getRecordMetadata().partition()).isEqualTo(first.getRecordMetadata().partition());
      assertThat(second.getRecordMetadata().offset()).isGreaterThan(first.getRecordMetadata().offset());
      partitions.add(first.getRecordMetadata().partition());
    }
    assertThat(partitions.stream().distinct()).hasSizeGreaterThan(1);
    assertThat(((org.springframework.kafka.listener.ConcurrentMessageListenerContainer<?,?>)listener).getConcurrency()).isEqualTo(3);
    listener.start();
    for(int i=0;i<requests.size();i++){
      MvcResult state=awaitRequestState(buyers.get(i).token(),requests.get(i),"ORDERED");
      assertThat(objectMapper.readTree(body(state)).get("status").asText()).isEqualTo("ORDERED");
    }
    for(long activity:activities){
      assertThat(storedOrders(activity)).hasSize(1);
      assertThat(storedInventory(activity).getAvailableStock()).isEqualTo(1);
    }
    } finally {
      if(!listener.isRunning())listener.start();
    }
  }

  private String gateState(long activity){return (String)redis.opsForHash().get("tokenx:seckill:{"+activity+"}:gate","state");}

  private MvcResult awaitRequestState(String token,UUID request,String expected) throws Exception {
    MvcResult result=null;long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
    while(System.nanoTime()<deadline){
      result=mockMvc.perform(get("/api/v1/order-requests/{id}",request).header("Authorization","Bearer "+token)).andReturn();
      if(objectMapper.readTree(body(result)).get("status").asText().equals(expected))return result;
      Thread.sleep(50);
    }
    return result;
  }
}
