package com.tokenx.messaging.mapper;

import java.nio.ByteBuffer;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for a consumer's idempotent event receipt. */
@Repository
public class ProcessedMessageMapper {

  private final JdbcTemplate jdbc;

  public ProcessedMessageMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void insert(String consumerName, UUID eventId, String eventType, int schemaVersion, LocalDateTime now) {
    jdbc.update(
        "INSERT INTO processed_message (consumer_name, event_id, event_type, schema_version, processed_at) "
            + "VALUES (?, ?, ?, ?, ?)",
        consumerName,
        uuidToBytes(eventId),
        eventType,
        schemaVersion,
        now);
  }

  public boolean exists(String consumerName, UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM processed_message WHERE consumer_name = ? AND event_id = ?",
            Integer.class,
            consumerName,
            uuidToBytes(eventId));
    return count != null && count > 0;
  }

  private static byte[] uuidToBytes(UUID uuid) {
    return ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array();
  }
}
