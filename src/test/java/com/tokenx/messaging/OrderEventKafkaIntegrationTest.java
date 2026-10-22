package com.tokenx.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import com.tokenx.messaging.config.MessagingProperties;
import com.tokenx.messaging.mapper.OutboxEventMapper.OutboxEventRecord;
import com.tokenx.messaging.model.OrderPaidEvent;
import com.tokenx.messaging.service.OutboxPublisher;
import com.tokenx.messaging.service.OrderEventKafkaSender;
import com.tokenx.order.AbstractOrderApiTest;
import com.tokenx.support.TestClockConfig;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.ObjectMapper;

/** Real Kafka 4.3.x + MySQL integration for Outbox retry, consumer restart and idempotency. */
@SpringBootTest(
    properties = {
      "spring.kafka.bootstrap-servers=127.0.0.1:19092",
      "spring.kafka.listener.auto-startup=true",
      "tokenx.messaging.kafka-enabled=true",
      "tokenx.messaging.listener-enabled=true",
      "tokenx.messaging.outbox-enabled=false",
      "tokenx.messaging.order-events-topic=tokenx.test.order-events.v1",
      "tokenx.messaging.order-events-dlt-topic=tokenx.test.order-events.dlt.v1",
      "tokenx.messaging.consumer-group-id=tokenx-test-order-events-v1",
      "tokenx.messaging.topic-replication-factor=1",
      "tokenx.messaging.listener-retry-delay=20ms",
      "tokenx.messaging.listener-retry-count=1",
      "tokenx.messaging.send-timeout=1s"
    })
@AutoConfigureMockMvc
class OrderEventKafkaIntegrationTest extends AbstractOrderApiTest {

  private static final String CONSUMER_NAME = "order-paid-receipt-v1";
  private static final String BROKER = "127.0.0.1:19092";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private KafkaTemplate<Object, Object> kafkaTemplate;
  @Autowired private OutboxPublisher outboxPublisher;
  @Autowired private MessagingProperties messagingProperties;
  @Autowired private KafkaListenerEndpointRegistry listenerRegistry;
  @Autowired private ObjectMapper objectMapper;

  private final java.util.Set<UUID> eventIds = new java.util.HashSet<>();
  private final java.util.Set<String> orderNumbers = new java.util.HashSet<>();

  @MockitoSpyBean private OrderEventKafkaSender kafkaSenderSpy;

  @AfterEach
  void cleanMessageRows() {
    for (UUID eventId : eventIds) {
      jdbc.update(
          "DELETE FROM processed_message WHERE consumer_name = ? AND event_id = ?",
          CONSUMER_NAME,
          uuidBytes(eventId));
      jdbc.update("DELETE FROM outbox_event WHERE event_id = ?", uuidBytes(eventId));
    }
    for (String orderNo : orderNumbers) {
      jdbc.update(
          "DELETE FROM processed_message WHERE event_id IN "
              + "(SELECT event_id FROM outbox_event WHERE aggregate_id = ?)",
          orderNo);
      jdbc.update("DELETE FROM outbox_event WHERE aggregate_id = ?", orderNo);
    }
    eventIds.clear();
    orderNumbers.clear();
  }

  @Test
  void shouldPublishCommittedPaymentAndSurviveConsumerRestartAndDuplicateDelivery()
      throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1200L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    orderNumbers.add(orderNo);
    mockPay(buyer.token(), orderNo).andReturn();

    OutboxEventRecord event = findOutbox(orderNo);
    eventIds.add(event.eventId());
    assertThat(processedCount(event.eventId())).isZero();

    outboxPublisher.publishBatch();
    await(() -> processedCount(event.eventId()) == 1, Duration.ofSeconds(10));
    assertThat(outboxStatus(orderNo)).isEqualTo(2);

    MessageListenerContainer container = listenerRegistry.getListenerContainers().iterator().next();
    container.stop();
    kafkaTemplate
        .send(messagingProperties.getOrderEventsTopic(), orderNo, event.payload())
        .get(3, TimeUnit.SECONDS);
    OrderPaidEvent barrierEvent =
        new OrderPaidEvent(
            1,
            UUID.randomUUID(),
            "ORDER_PAID",
            "barrier-" + UUID.randomUUID(),
            "NORMAL",
            1L,
            1L,
            java.time.Instant.parse("2026-10-21T12:00:00Z"));
    eventIds.add(barrierEvent.eventId());
    String barrierPayload = objectMapper.writeValueAsString(barrierEvent);
    String barrierKey = barrierEvent.orderNo();
    kafkaTemplate
        .send(messagingProperties.getOrderEventsTopic(), barrierKey, barrierPayload)
        .get(3, TimeUnit.SECONDS);
    container.start();
    await(() -> processedCount(barrierEvent.eventId()) == 1, Duration.ofSeconds(10));
    assertThat(processedCount(event.eventId()))
        .as("a repeated Kafka record after consumer restart is idempotent")
        .isEqualTo(1);
  }

  @Test
  void shouldKeepOutboxEventAndRetryAfterKafkaSendFailure() throws Exception {
    Buyer buyer = registerBuyer();
    long packageId = packageRow(1400L);
    String orderNo = orderNoOf(placeNormalOrder(buyer.token(), packageId, newKey()).andReturn());
    orderNumbers.add(orderNo);
    mockPay(buyer.token(), orderNo).andReturn();
    OutboxEventRecord event = findOutbox(orderNo);
    eventIds.add(event.eventId());

    doThrow(new IllegalStateException("simulated broker outage"))
        .doCallRealMethod()
        .when(kafkaSenderSpy)
        .send(
            eq(messagingProperties.getOrderEventsTopic()),
            eq(orderNo),
            eq(event.payload()));

    assertThat(outboxPublisher.publishBatch()).isGreaterThan(0);
    assertThat(outboxStatus(orderNo)).isZero();
    assertThat(outboxAttempts(orderNo)).isEqualTo(1);

    jdbc.update(
        "UPDATE outbox_event SET next_attempt_at = ? WHERE event_id = ?",
        java.time.LocalDateTime.ofInstant(BASE.plusSeconds(2), java.time.ZoneOffset.UTC),
        uuidBytes(event.eventId()));
    TestClockConfig.setTo(BASE.plusSeconds(3));
    assertThat(outboxPublisher.publishBatch()).isGreaterThan(0);
    assertThat(outboxStatus(orderNo)).isEqualTo(2);
    assertThat(outboxAttempts(orderNo)).isEqualTo(2);
    await(() -> processedCount(event.eventId()) == 1, Duration.ofSeconds(10));
    assertThat(outboxStatus(orderNo)).isEqualTo(2);
    assertThat(outboxAttempts(orderNo)).isEqualTo(2);
  }

  @Test
  void shouldRetryPoisonRecordOnlyAFiniteNumberOfTimesThenPublishToDlt() throws Exception {
    String poison = "{invalid-json";
    String key = "poison-" + UUID.randomUUID();
    try (KafkaConsumer<String, String> dltConsumer = newDltConsumer()) {
      dltConsumer.subscribe(List.of(messagingProperties.getOrderEventsDltTopic()));
      dltConsumer.poll(Duration.ofMillis(200));
      kafkaTemplate
          .send(messagingProperties.getOrderEventsTopic(), key, poison)
          .get(3, TimeUnit.SECONDS);
      ConsumerRecord<String, String> deadLetter = pollUntilDlt(dltConsumer, poison);
      assertThat(deadLetter.value()).isEqualTo(poison);
      assertThat(deadLetter.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
    }
  }

  private OutboxEventRecord findOutbox(String orderNo) {
    return jdbc.queryForObject(
        "SELECT id, event_id, event_type, schema_version, message_key, payload, attempt_count "
            + "FROM outbox_event WHERE aggregate_id = ?",
        (row, rowNumber) ->
            new OutboxEventRecord(
                row.getLong("id"),
                uuidFromBytes(row.getBytes("event_id")),
                row.getString("event_type"),
                row.getInt("schema_version"),
                row.getString("message_key"),
                row.getString("payload"),
                row.getInt("attempt_count")),
        orderNo);
  }

  private int processedCount(UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_message WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            CONSUMER_NAME,
            uuidBytes(eventId));
    return count == null ? 0 : count;
  }

  private int outboxStatus(String orderNo) {
    return jdbc.queryForObject(
        "SELECT status FROM outbox_event WHERE aggregate_id = ?", Integer.class, orderNo);
  }

  private int outboxAttempts(String orderNo) {
    return jdbc.queryForObject(
        "SELECT attempt_count FROM outbox_event WHERE aggregate_id = ?", Integer.class, orderNo);
  }

  private KafkaConsumer<String, String> newDltConsumer() {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BROKER);
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID());
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new KafkaConsumer<>(properties);
  }

  private ConsumerRecord<String, String> pollUntilDlt(
      KafkaConsumer<String, String> consumer, String expectedValue) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
        if (expectedValue.equals(record.value())) {
          return record;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("message was not published to DLT before timeout");
  }

  private static void await(BooleanSupplier condition, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      Thread.sleep(25);
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private static byte[] uuidBytes(UUID uuid) {
    return java.nio.ByteBuffer.allocate(16)
        .putLong(uuid.getMostSignificantBits())
        .putLong(uuid.getLeastSignificantBits())
        .array();
  }

  private static UUID uuidFromBytes(byte[] bytes) {
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }
}
