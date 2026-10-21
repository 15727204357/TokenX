package com.tokenx.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tokenx.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What the database itself guarantees about a wallet posting.
 *
 * <p>The ledger is the evidence a balance is right, so every rule that keeps it honest is asserted
 * against real MySQL rather than trusted to the migration: an in-memory database would accept an
 * unknown business type, ignore the {@code CHECK} on the delta sign, and let the same order credit
 * the same account twice.
 *
 * <p>Rows are inserted with plain SQL, so a rejected insert is rejected by the database and not by a
 * service that happened to validate first.
 */
@SpringBootTest
class TokenLedgerSchemaTest extends AbstractIntegrationTest {

  /** Every row here belongs to this user id range, which no fixture ever uses. */
  private static final AtomicLong USER_ID = new AtomicLong(9_000_000L);

  @Autowired private DataSource dataSource;

  private final List<Long> userIds = new ArrayList<>();

  @AfterEach
  void deleteRowsCreatedByThisTest() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement("DELETE FROM token_ledger WHERE user_id = ?")) {
      for (Long userId : userIds) {
        statement.setLong(1, userId);
        statement.addBatch();
      }
      statement.executeBatch();
    }
    userIds.clear();
  }

  @Test
  void shouldDeclareTheDocumentedColumnTypes() throws SQLException {
    assertThat(columnType("id")).isEqualTo("bigint unsigned");
    assertThat(columnType("user_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("biz_type")).isEqualTo("tinyint");
    assertThat(columnType("biz_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("effect_type")).isEqualTo("tinyint");
    assertThat(columnType("delta"))
        .as("a balance change is signed, unlike a balance")
        .isEqualTo("bigint");
    assertThat(columnType("balance_after")).isEqualTo("bigint unsigned");
    assertThat(columnType("created_at")).isEqualTo("datetime(3)");

    assertThat(isNullable("delta")).isFalse();
    assertThat(isNullable("balance_after")).isFalse();
    assertThat(isNullable("created_at")).isFalse();
  }

  @Test
  void shouldDeclareExactlyTheDocumentedKeys() throws SQLException {
    assertThat(indexNames()).containsExactlyInAnyOrder("PRIMARY", "uk_ledger_biz", "idx_ledger_user");

    assertThat(indexColumns("PRIMARY")).containsExactly("id");
    assertThat(indexColumns("uk_ledger_biz"))
        .as("one posting per business row and effect: the second line of defence against double posting")
        .containsExactly("biz_type", "biz_id", "effect_type");
    assertThat(indexColumns("idx_ledger_user"))
        .as("the cursor walk filters by user and orders by id, which the stored primary key supplies")
        .containsExactly("user_id");
  }

  @Test
  void shouldHaveNoPhysicalForeignKey() throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery(
                "SELECT constraint_name FROM information_schema.table_constraints"
                    + " WHERE table_schema = DATABASE() AND table_name = 'token_ledger'"
                    + " AND constraint_type = 'FOREIGN KEY'")) {
      assertThat(rs.next())
          .as("referential integrity is the service's job, exactly like purchase_order")
          .isFalse();
    }
  }

  @Test
  void shouldRejectAnUnknownBusinessTypeOrEffect() {
    long userId = newUser();

    assertThatThrownBy(() -> insert(userId, 2, 1L, 1, 100L, 100L))
        .as("only 1=ORDER has a meaning in V1")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insert(userId, 1, 1L, 3, 100L, 100L))
        .as("only 1=CREDIT and 2=DEBIT may be stored")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRequireTheDeltaSignToMatchTheEffect() {
    long userId = newUser();

    assertThatThrownBy(() -> insert(userId, 1, 10L, 1, -100L, 900L))
        .as("a credit that lowers the balance would make the ledger unreadable")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insert(userId, 1, 11L, 1, 0L, 1000L))
        .as("a posting that changes nothing is not evidence of anything")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insert(userId, 1, 12L, 2, 100L, 1000L))
        .as("a debit must be negative")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRefuseASecondCreditForTheSameOrder() throws SQLException {
    long userId = newUser();

    insert(userId, 1, 20L, 1, 100L, 1100L);
    assertThatThrownBy(() -> insert(userId, 1, 20L, 1, 100L, 1200L))
        .as("uk_ledger_biz is what makes 'paid twice' impossible to record")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldAllowADifferentEffectOnTheSameBusinessRow() throws SQLException {
    long userId = newUser();

    insert(userId, 1, 30L, 1, 100L, 1100L);
    assertThat(insert(userId, 1, 30L, 2, -100L, 1000L))
        .as("a future reversal is a different effect, so the key must allow it")
        .isPositive();
  }

  @Test
  void shouldGenerateTheIdAndKeepTheServerDefaultTimestamp() throws SQLException {
    long userId = newUser();

    assertThat(insert(userId, 1, 40L, 1, 100L, 100L)).isPositive();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM token_ledger WHERE user_id = ? AND created_at IS NOT NULL")) {
      statement.setLong(1, userId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        assertThat(rs.getLong(1)).isEqualTo(1);
      }
    }
  }

  // --- helpers --------------------------------------------------------------------------------

  private long newUser() {
    long userId = USER_ID.incrementAndGet();
    userIds.add(userId);
    return userId;
  }

  /**
   * Inserts one posting and returns its generated id.
   *
   * <p>{@code created_at} is deliberately left to the column default: this test is about the schema,
   * and the application writes that column from its own clock.
   */
  private long insert(
      long userId, int bizType, long bizId, int effectType, long delta, long balanceAfter)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "INSERT INTO token_ledger"
                    + " (user_id, biz_type, biz_id, effect_type, delta, balance_after)"
                    + " VALUES (?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
      statement.setLong(1, userId);
      statement.setInt(2, bizType);
      statement.setLong(3, bizId);
      statement.setInt(4, effectType);
      statement.setLong(5, delta);
      statement.setLong(6, balanceAfter);
      statement.executeUpdate();
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
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
                + " WHERE table_schema = DATABASE() AND table_name = 'token_ledger'"
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
            + " WHERE table_schema = DATABASE() AND table_name = 'token_ledger'");
  }

  private List<String> indexColumns(String index) throws SQLException {
    return query(
        "SELECT column_name FROM information_schema.statistics"
            + " WHERE table_schema = DATABASE() AND table_name = 'token_ledger'"
            + " AND index_name = '"
            + index
            + "' ORDER BY seq_in_index");
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
