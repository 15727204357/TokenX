package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tokenx.support.AbstractIntegrationTest;
import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What the database itself guarantees about an order.
 *
 * <p>Every constraint below is a line of defence that survives a bug in the service, so it is worth
 * asserting against real MySQL rather than trusting the migration: an in-memory database would
 * accept the unknown status, ignore the {@code CHECK}s, treat {@code order_no} case-insensitively
 * and let a {@code NORMAL} order claim an activity.
 *
 * <p>The rows here are inserted with plain SQL, so a rejected insert is rejected by the database and
 * not by an annotation.
 */
@SpringBootTest
class PurchaseOrderSchemaTest extends AbstractIntegrationTest {

  private static final String EXPIRES_AT = "2026-10-21 12:15:00.000";

  private static final String PAID_AT = "2026-10-21 12:05:00.000";

  private static final String CLOSED_AT = "2026-10-21 12:06:00.000";

  @Autowired private DataSource dataSource;

  private final AtomicInteger sequence = new AtomicInteger();

  private final List<String> orderNos = new ArrayList<>();

  @AfterEach
  void deleteRowsCreatedByThisTest() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM purchase_order WHERE order_no = ?")) {
      for (String orderNo : orderNos) {
        statement.setString(1, orderNo);
        statement.addBatch();
      }
      statement.executeBatch();
    }
    orderNos.clear();
  }

  @Test
  void shouldDeclareTheDocumentedColumnTypes() throws SQLException {
    assertThat(columnType("id")).isEqualTo("bigint unsigned");
    assertThat(columnType("order_no")).isEqualTo("char(26)");
    assertThat(columnType("request_id")).isEqualTo("binary(16)");
    assertThat(columnType("user_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("package_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("activity_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("order_type")).isEqualTo("tinyint");
    assertThat(columnType("package_name_snapshot")).isEqualTo("varchar(96)");
    assertThat(columnType("credit_amount")).isEqualTo("bigint unsigned");
    assertThat(columnType("pay_amount_cent")).isEqualTo("bigint unsigned");
    assertThat(columnType("status")).isEqualTo("tinyint");
    assertThat(columnType("expires_at")).isEqualTo("datetime(3)");
    assertThat(columnType("paid_at")).isEqualTo("datetime(3)");
    assertThat(columnType("closed_at")).isEqualTo("datetime(3)");
    assertThat(columnType("created_at")).isEqualTo("datetime(3)");
    assertThat(columnType("updated_at")).isEqualTo("datetime(3)");

    assertThat(isNullable("activity_id")).as("normal purchases have no activity").isTrue();
    assertThat(isNullable("expires_at")).isFalse();
    assertThat(isNullable("paid_at")).isTrue();
    assertThat(isNullable("closed_at")).isTrue();
  }

  @Test
  void shouldStoreTheOrderNumberCaseSensitivelyInAscii() throws SQLException {
    assertThat(columnValue("character_set_name", "order_no")).isEqualTo("ascii");
    assertThat(columnValue("collation_name", "order_no"))
        .as("a case-sensitive collation keeps the generated alphabet unambiguous")
        .isEqualTo("ascii_bin");

    String upper = newOrderNo();
    String lower = upper.toLowerCase();
    insertOrder(upper, randomRequestId(), 41L, 900L, 1, 1000L, 799L, 0, null, null);
    insertOrder(lower, randomRequestId(), 42L, 900L, 1, 1000L, 799L, 0, null, null);

    assertThat(countOrders(upper, lower))
        .as("both spellings are distinct rows under ascii_bin, so neither is a duplicate")
        .isEqualTo(2);
  }

  @Test
  void shouldRejectANonAsciiOrderNumber() {
    String multibyte = "é" + newOrderNo().substring(1);

    assertThatThrownBy(
            () -> insertOrder(multibyte, randomRequestId(), 43L, 900L, 1, 1000L, 799L, 0, null, null))
        .as("the column is ascii: a client cannot smuggle a multibyte identifier in")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldDeclareExactlyTheDocumentedSecondaryIndexes() throws SQLException {
    assertThat(indexNames())
        .containsExactlyInAnyOrder(
            "PRIMARY",
            "uk_order_no",
            "uk_user_request",
            "uk_activity_user",
            "idx_order_user",
            "idx_order_timeout");
  }

  @Test
  void shouldOrderCompositeIndexColumnsAsDocumented() throws SQLException {
    assertThat(indexColumns("uk_user_request")).containsExactly("user_id", "request_id");
    assertThat(indexColumns("uk_activity_user")).containsExactly("activity_id", "user_id");
    assertThat(indexColumns("idx_order_user")).containsExactly("user_id");
    assertThat(indexColumns("idx_order_timeout"))
        .as("the timeout scan filters on status and ranges over expires_at")
        .containsExactly("status", "expires_at");
  }

  @Test
  void shouldDeclareTheEnumAmountAndActivityChecks() throws SQLException {
    String clauses = String.join("\n", checkClauses()).toLowerCase();

    assertThat(clauses).contains("order_type").contains("status").contains("activity_id");
    assertThat(clauses).contains("credit_amount").contains("pay_amount_cent");
    assertThat(clauses).contains("paid_at").contains("closed_at");
  }

  @Test
  void shouldRejectAnUnknownOrderTypeOrStatus() {
    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 51L, 901L, 3, 1000L, 799L, 0, null, null))
        .as("only 1=SECKILL and 2=NORMAL may be stored")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 52L, 901L, 1, 1000L, 799L, 9, null, null))
        .as("only 0=UNPAID, 1=PAID and 2=CLOSED may be stored")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRequireAnActivityExactlyForSeckillOrders() throws SQLException {
    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 61L, null, 1, 1000L, 799L, 0, null, null))
        .as("a seckill order without an activity has no stock behind it")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 62L, 902L, 2, 1000L, 799L, 0, null, null))
        .as("a normal order must not pretend to belong to an activity")
        .isInstanceOf(SQLException.class);

    assertThat(
            insertOrder(newOrderNo(), randomRequestId(), 63L, null, 2, 1000L, 799L, 0, null, null))
        .as("a normal order is the legal case: type 2 with no activity")
        .isPositive();
  }

  @Test
  void shouldRejectOutOfRangeAmounts() {
    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 71L, 903L, 1, 0L, 799L, 0, null, null))
        .as("credits must be positive")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () ->
                insertOrder(
                    newOrderNo(), randomRequestId(), 72L, 903L, 1, 1_000_000_001L, 799L, 0, null, null))
        .as("credits are capped so the browser can represent them")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 73L, 903L, 1, 1000L, 0L, 0, null, null))
        .as("the payable amount must be positive")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () ->
                insertOrder(
                    newOrderNo(), randomRequestId(), 74L, 903L, 1, 1000L, 100_000_000_001L, 0, null, null))
        .as("the payable amount is capped like every other price")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldKeepTerminalTimestampsConsistentWithTheStatus() {
    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 81L, 904L, 1, 1000L, 799L, 0, PAID_AT, null))
        .as("an unpaid order cannot already record a payment")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 82L, 904L, 1, 1000L, 799L, 1, null, null))
        .as("a paid order must record when it was paid")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () ->
                insertOrder(
                    newOrderNo(), randomRequestId(), 83L, 904L, 1, 1000L, 799L, 1, PAID_AT, CLOSED_AT))
        .as("a paid order cannot also look closed")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 84L, 904L, 1, 1000L, 799L, 2, null, null))
        .as("a closed order must record when it was closed")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldAllowManyNormalOrdersButOnlyOneSeckillOrderPerActivityAndUser() throws SQLException {
    // NULL activity_id does not participate in a MySQL unique index, which is exactly what the
    // normal purchase channel needs: one user may buy the same package many times.
    insertOrder(newOrderNo(), randomRequestId(), 91L, null, 2, 1000L, 799L, 0, null, null);
    insertOrder(newOrderNo(), randomRequestId(), 91L, null, 2, 1000L, 799L, 0, null, null);

    assertThat(countSeckillOrders(91L, 905L)).isZero();
    insertOrder(newOrderNo(), randomRequestId(), 91L, 905L, 1, 1000L, 799L, 0, null, null);
    assertThat(countSeckillOrders(91L, 905L)).isEqualTo(1);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), randomRequestId(), 91L, 905L, 1, 1000L, 799L, 0, null, null))
        .as("uk_activity_user is the permanent one-order-per-user rule")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldScopeTheIdempotencyKeyToTheAuthenticatedUser() throws SQLException {
    byte[] requestId = randomRequestId();

    insertOrder(newOrderNo(), requestId, 101L, null, 2, 1000L, 799L, 0, null, null);
    insertOrder(newOrderNo(), requestId, 102L, null, 2, 1000L, 799L, 0, null, null);

    assertThatThrownBy(
            () -> insertOrder(newOrderNo(), requestId, 101L, null, 2, 1000L, 799L, 0, null, null))
        .as("one key cannot produce two orders for the same user")
        .isInstanceOf(SQLException.class);
  }

  // --- helpers --------------------------------------------------------------------------------

  /** 26 Crockford Base32 characters, unique per call, wide enough to stay readable in a failure. */
  private String newOrderNo() {
    return "ABCDEFGHJK" + String.format("%016d", sequence.incrementAndGet());
  }

  private static byte[] randomRequestId() {
    UUID value = UUID.randomUUID();
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  private long insertOrder(
      String orderNo,
      byte[] requestId,
      long userId,
      Long activityId,
      int orderType,
      long creditAmount,
      long payAmountCent,
      int status,
      String paidAt,
      String closedAt)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO purchase_order"
                    + " (order_no, request_id, user_id, package_id, activity_id, order_type,"
                    + "  package_name_snapshot, credit_amount, pay_amount_cent, status, expires_at,"
                    + "  paid_at, closed_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
      statement.setString(1, orderNo);
      statement.setBytes(2, requestId);
      statement.setLong(3, userId);
      statement.setLong(4, 900L);
      if (activityId == null) {
        statement.setNull(5, Types.BIGINT);
      } else {
        statement.setLong(5, activityId);
      }
      statement.setInt(6, orderType);
      statement.setString(7, "模式套餐");
      statement.setLong(8, creditAmount);
      statement.setLong(9, payAmountCent);
      statement.setInt(10, status);
      statement.setString(11, EXPIRES_AT);
      statement.setString(12, paidAt);
      statement.setString(13, closedAt);
      statement.executeUpdate();
      // Only a row that really landed is worth deleting: a rejected insert must not leave a
      // non-ASCII value behind for the cleanup statement to choke on.
      orderNos.add(orderNo);
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private long countOrders(String first, String second) throws SQLException {
    return count(
        "SELECT COUNT(*) FROM purchase_order WHERE order_no IN ('" + first + "', '" + second + "')");
  }

  private long countSeckillOrders(long userId, long activityId) throws SQLException {
    return count(
        "SELECT COUNT(*) FROM purchase_order WHERE user_id = "
            + userId
            + " AND activity_id = "
            + activityId);
  }

  private long count(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private String columnType(String column) throws SQLException {
    return columnValue("column_type", column);
  }

  private String columnValue(String what, String column) throws SQLException {
    return query(
            "SELECT "
                + what
                + " FROM information_schema.columns"
                + " WHERE table_schema = DATABASE() AND table_name = 'purchase_order'"
                + " AND column_name = '"
                + column
                + "'")
        .stream()
        .findFirst()
        .orElse(null);
  }

  private boolean isNullable(String column) throws SQLException {
    return "YES".equals(columnValue("is_nullable", column));
  }

  private List<String> indexNames() throws SQLException {
    return query(
        "SELECT DISTINCT index_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'purchase_order'");
  }

  private List<String> indexColumns(String index) throws SQLException {
    return query(
        "SELECT column_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'purchase_order'"
            + " AND index_name = '"
            + index
            + "' ORDER BY seq_in_index");
  }

  private List<String> checkClauses() throws SQLException {
    return query(
            "SELECT cc.check_clause FROM information_schema.check_constraints cc"
                + " JOIN information_schema.table_constraints tc"
                + "   ON tc.constraint_schema = cc.constraint_schema"
                + "  AND tc.constraint_name = cc.constraint_name"
                + " WHERE cc.constraint_schema = DATABASE()"
                + "   AND tc.table_name = 'purchase_order'")
        .stream()
        .map(clause -> clause.replace("`", ""))
        .toList();
  }

  private List<String> query(String sql) throws SQLException {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs = statement.executeQuery(sql)) {
      while (rs.next()) {
        values.add(rs.getString(1));
      }
    }
    return values;
  }
}
