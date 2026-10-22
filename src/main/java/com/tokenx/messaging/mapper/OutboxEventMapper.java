package com.tokenx.messaging.mapper;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Database boundary for transactional Outbox records and publisher leases. */
@Repository
public class OutboxEventMapper {

  private static final RowMapper<OutboxEventRecord> ROW_MAPPER =
      (row, rowNumber) ->
          new OutboxEventRecord(
              row.getLong("id"),
              uuidFromBytes(row.getBytes("event_id")),
              row.getString("event_type"),
              row.getInt("schema_version"),
              row.getString("message_key"),
              row.getString("payload"),
              row.getInt("attempt_count"));

  private final JdbcTemplate jdbc;

  public OutboxEventMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insertOrderPaid(UUID eventId, String orderNo, String payload, LocalDateTime now) {
    jdbc.update(
        "INSERT INTO outbox_event (event_id, aggregate_type, aggregate_id, event_type, "
            + "schema_version, message_key, payload, status, attempt_count, next_attempt_at, "
            + "created_at, updated_at) VALUES (?, 'ORDER', ?, 'ORDER_PAID', 1, ?, ?, 0, 0, ?, ?, ?)",
        uuidToBytes(eventId),
        orderNo,
        orderNo,
        payload,
        now,
        now,
        now);
  }

  /** Claims a bounded set. The caller must wrap this call in a short MySQL transaction. */
  public List<OutboxEventRecord> claimDue(
      LocalDateTime now, LocalDateTime leaseUntil, int batchSize) {
    List<OutboxEventRecord> due =
        jdbc.query(
            "SELECT id, event_id, event_type, schema_version, message_key, payload, attempt_count "
                + "FROM outbox_event WHERE (status = 0 AND next_attempt_at <= ?) "
                + "OR (status = 1 AND lease_until <= ?) ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
            ROW_MAPPER,
            now,
            now,
            batchSize);
    for (OutboxEventRecord event : due) {
      jdbc.update(
          "UPDATE outbox_event SET status = 1, lease_until = ?, "
              + "attempt_count = LEAST(attempt_count + 1, 10) "
              + "WHERE id = ?",
          leaseUntil,
          event.id());
    }
    return due.stream()
        .map(
            event ->
                new OutboxEventRecord(
                    event.id(),
                    event.eventId(),
                    event.eventType(),
                    event.schemaVersion(),
                    event.messageKey(),
                    event.payload(),
                    Math.min(event.attemptCount() + 1, 10)))
        .toList();
  }

  public void markPublished(long id, LocalDateTime publishedAt) {
    jdbc.update(
        "UPDATE outbox_event SET status = 2, lease_until = NULL, published_at = ?, "
            + "last_error_code = NULL WHERE id = ? AND status = 1",
        publishedAt,
        id);
  }

  public void scheduleRetry(long id, LocalDateTime nextAttemptAt, String errorCode) {
    jdbc.update(
        "UPDATE outbox_event SET status = 0, lease_until = NULL, next_attempt_at = ?, "
            + "last_error_code = ? WHERE id = ? AND status = 1",
        nextAttemptAt,
        errorCode,
        id);
  }

  public void markDeadLettered(long id, String errorCode) {
    jdbc.update(
        "UPDATE outbox_event SET status = 3, lease_until = NULL, last_error_code = ? "
            + "WHERE id = ? AND status = 1",
        errorCode,
        id);
  }

  private static byte[] uuidToBytes(UUID uuid) {
    return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
  }

  private static UUID uuidFromBytes(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }

  public record OutboxEventRecord(
      long id,
      UUID eventId,
      String eventType,
      int schemaVersion,
      String messageKey,
      String payload,
      int attemptCount) {}
}
