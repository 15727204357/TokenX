package com.tokenx.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.activity.mapper.ActivityInventoryMapper;
import com.tokenx.activity.mapper.SeckillActivityMapper;
import com.tokenx.activity.model.SeckillActivity;
import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.infrastructure.money.AmountLimits;
import com.tokenx.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What the database itself guarantees for activities and stock.
 *
 * <p>Every assertion runs against real MySQL: an in-memory database would not apply these
 * {@code CHECK} constraints, the unsigned column types, the collation or the index definitions.
 * Application validation is tested separately; this class proves the last line of defence.
 */
@SpringBootTest
class ActivitySchemaTest extends AbstractIntegrationTest {

  private static final String START = "2026-10-21 13:00:00.000";
  private static final String END = "2026-10-21 14:00:00.000";

  @Autowired private DataSource dataSource;
  @Autowired private SeckillActivityMapper activityMapper;
  @Autowired private ActivityInventoryMapper inventoryMapper;
  @Autowired private TokenPackageMapper packageMapper;

  private final List<Long> packageIds = new ArrayList<>();

  @AfterEach
  void deleteRowsCreatedByThisTest() {
    for (Long packageId : packageIds) {
      List<SeckillActivity> activities =
          activityMapper.selectList(
              Wrappers.<SeckillActivity>lambdaQuery().eq(SeckillActivity::getPackageId, packageId));
      for (SeckillActivity activity : activities) {
        inventoryMapper.deleteById(activity.getId());
        activityMapper.deleteById(activity.getId());
      }
      packageMapper.deleteById(packageId);
    }
    packageIds.clear();
  }

  @Test
  void shouldDeclareTheDocumentedColumnTypes() throws SQLException {
    assertThat(columnType("seckill_activity", "id")).isEqualTo("bigint unsigned");
    assertThat(columnType("seckill_activity", "package_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("seckill_activity", "activity_price_cent")).isEqualTo("bigint unsigned");
    assertThat(columnType("seckill_activity", "credit_amount_snapshot")).isEqualTo("bigint unsigned");
    assertThat(columnType("seckill_activity", "start_at")).isEqualTo("datetime(3)");
    assertThat(columnType("seckill_activity", "end_at")).isEqualTo("datetime(3)");
    assertThat(columnType("seckill_activity", "status")).isEqualTo("tinyint");
    assertThat(columnType("seckill_activity", "created_at")).isEqualTo("datetime(3)");
    assertThat(columnType("seckill_activity", "updated_at")).isEqualTo("datetime(3)");

    assertThat(columnType("activity_inventory", "activity_id")).isEqualTo("bigint unsigned");
    assertThat(columnType("activity_inventory", "total_stock")).isEqualTo("int unsigned");
    assertThat(columnType("activity_inventory", "available_stock")).isEqualTo("int unsigned");
    assertThat(columnType("activity_inventory", "reserved_stock")).isEqualTo("int unsigned");
    assertThat(columnType("activity_inventory", "updated_at")).isEqualTo("datetime(3)");
  }

  @Test
  void shouldDeclareTheActivityListIndexInTheRightOrder() throws SQLException {
    assertThat(indexColumns("seckill_activity", "idx_activity_list"))
        .as("the list query filters on status and orders by start_at")
        .containsExactly("status", "start_at");
  }

  @Test
  void shouldKeepTheInventoryTableAtItsPrimaryKeyOnly() throws SQLException {
    assertThat(indexNames("activity_inventory"))
        .as("a secondary index on a hot stock row would cost writes for no query")
        .containsExactly("PRIMARY");
  }

  @Test
  void shouldDeclareTheStatusAndWindowChecks() throws SQLException {
    String clauses = String.join("\n", checkClauses("seckill_activity")).toLowerCase();

    assertThat(clauses).contains("status").contains("start_at < end_at");
    assertThat(clauses).contains("activity_price_cent").contains("credit_amount_snapshot");
    assertThat(clauses).contains("100000000000").contains("1000000000");
  }

  @Test
  void shouldRejectUnknownStatusAtTheDatabaseLevel() throws SQLException {
    long packageId = packageRow();

    assertThatThrownBy(
            () -> insertActivity(packageId, 100L, 1000L, START, END, 9))
        .as("only DRAFT/PUBLISHED/OFFLINE may be stored")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRejectAnInvalidTimeWindowAtTheDatabaseLevel() {
    long packageId = packageRow();

    assertThatThrownBy(() -> insertActivity(packageId, 100L, 1000L, END, START, 0))
        .as("start must be before end")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insertActivity(packageId, 100L, 1000L, START, START, 0))
        .as("an empty window is not a window")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRejectOutOfRangeAmountsAtTheDatabaseLevel() {
    long packageId = packageRow();

    assertThatThrownBy(() -> insertActivity(packageId, 0L, 1000L, START, END, 0))
        .as("the activity price must be positive")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () -> insertActivity(packageId, AmountLimits.MAX_PRICE_CENT + 1, 1000L, START, END, 0))
        .as("the activity price is capped so the browser can represent it")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insertActivity(packageId, 100L, 0L, START, END, 0))
        .as("the credit snapshot must be positive")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(
            () ->
                insertActivity(
                    packageId, 100L, AmountLimits.MAX_CREDIT_AMOUNT + 1, START, END, 0))
        .as("the credit snapshot is capped like every other credit amount")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldAcceptAmountsExactlyAtTheirCaps() throws SQLException {
    long packageId = packageRow();

    long id =
        insertActivity(
            packageId, AmountLimits.MAX_PRICE_CENT, AmountLimits.MAX_CREDIT_AMOUNT, START, END, 0);

    SeckillActivity stored = activityMapper.selectById(id);
    assertThat(stored.getActivityPriceCent()).isEqualTo(AmountLimits.MAX_PRICE_CENT);
    assertThat(stored.getCreditAmountSnapshot()).isEqualTo(AmountLimits.MAX_CREDIT_AMOUNT);
  }

  @Test
  void shouldRejectNegativeOrInconsistentStockAtTheDatabaseLevel() throws SQLException {
    long id = insertActivity(packageRow(), 100L, 1000L, START, END, 0);

    assertThatThrownBy(() -> insertInventory(id, 10, -1, 0))
        .as("the unsigned column type rejects a negative total")
        .isInstanceOf(Exception.class);

    assertThatThrownBy(() -> insertInventory(id, 10, 10, 1))
        .as("available + reserved may not exceed the total")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insertInventory(id, 10, 6, 5))
        .as("6 available + 5 reserved > 10 total")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldAcceptAFullyConsistentStockRow() throws SQLException {
    long id = insertActivity(packageRow(), 100L, 1000L, START, END, 0);

    insertInventory(id, 10, 7, 3);

    assertThat(inventoryMapper.selectById(id).getTotalStock()).isEqualTo(10);
  }

  // --- helpers --------------------------------------------------------------------------------

  private long packageRow() {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName("模式套餐-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    tokenPackage.setCreditAmount(1000L);
    tokenPackage.setListPriceCent(999L);
    tokenPackage.setStatus(PackageStatus.ON_SALE);
    packageMapper.insert(tokenPackage);
    packageIds.add(tokenPackage.getId());
    return tokenPackage.getId();
  }

  /** Inserts through SQL so the failure comes from the database, not from the API validation. */
  private long insertActivity(
      long packageId,
      long priceCent,
      long creditSnapshot,
      String startAt,
      String endAt,
      int status)
      throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO seckill_activity"
              + "(package_id, activity_price_cent, credit_amount_snapshot, start_at, end_at, status)"
              + " VALUES ("
              + packageId
              + ", "
              + priceCent
              + ", "
              + creditSnapshot
              + ", '"
              + startAt
              + "', '"
              + endAt
              + "', "
              + status
              + ")",
          Statement.RETURN_GENERATED_KEYS);
      try (ResultSet keys = statement.getGeneratedKeys()) {
        keys.next();
        return keys.getLong(1);
      }
    }
  }

  private void insertInventory(long activityId, int total, int available, int reserved)
      throws SQLException {
    execute(
        "INSERT INTO activity_inventory(activity_id, total_stock, available_stock, reserved_stock)"
            + " VALUES ("
            + activityId
            + ", "
            + total
            + ", "
            + available
            + ", "
            + reserved
            + ")");
  }

  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }

  private List<String> indexNames(String table) throws SQLException {
    return query(
        "SELECT DISTINCT index_name FROM information_schema.statistics "
            + "WHERE table_schema = DATABASE() AND table_name = '"
            + table
            + "' ORDER BY index_name");
  }

  private List<String> indexColumns(String table, String index) throws SQLException {
    return query(
        "SELECT column_name FROM information_schema.statistics "
            + "WHERE table_schema = DATABASE() AND table_name = '"
            + table
            + "' AND index_name = '"
            + index
            + "' ORDER BY seq_in_index");
  }

  private List<String> checkClauses(String table) throws SQLException {
    // MySQL renders identifiers with backticks; strip them so the assertions read like the DDL.
    return query(
            "SELECT cc.check_clause FROM information_schema.check_constraints cc "
                + "JOIN information_schema.table_constraints tc "
                + "  ON tc.constraint_schema = cc.constraint_schema "
                + " AND tc.constraint_name = cc.constraint_name "
                + "WHERE cc.constraint_schema = DATABASE() AND tc.table_name = '"
                + table
                + "'")
        .stream()
        .map(clause -> clause.replace("`", ""))
        .toList();
  }

  private String columnType(String table, String column) throws SQLException {
    return query(
            "SELECT column_type FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'")
        .stream()
        .findFirst()
        .orElse(null);
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
