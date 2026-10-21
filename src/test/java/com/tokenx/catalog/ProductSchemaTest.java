package com.tokenx.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tokenx.catalog.mapper.TokenPackageMapper;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
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
 * Database guarantees behind the catalogue: the visibility index, the status and positive-value
 * checks, and the fact that duplicate names are legal.
 *
 * <p>These are asserted against real MySQL, because a mock or an in-memory database would not apply
 * this migration's constraints or collation.
 */
@SpringBootTest
class ProductSchemaTest extends AbstractIntegrationTest {

  @Autowired private DataSource dataSource;
  @Autowired private TokenPackageMapper packageMapper;

  private final List<Long> createdIds = new ArrayList<>();

  @AfterEach
  void deleteCreatedRows() {
    for (Long id : createdIds) {
      packageMapper.deleteById(id);
    }
    createdIds.clear();
  }

  @Test
  void shouldDeclareTheVisibilityIndex() throws SQLException {
    assertThat(indexNames("token_package"))
        .as("the public list filters on status, so the index must exist")
        .contains("idx_package_status");
  }

  @Test
  void shouldDeclareStatusAndPositiveValueChecks() throws SQLException {
    String clauses = String.join("\n", checkClauses("token_package"));

    assertThat(clauses).contains("status");
    assertThat(clauses).contains("credit_amount");
    assertThat(clauses).contains("list_price_cent");
  }

  @Test
  void shouldRejectUnknownStatusAtTheDatabaseLevel() throws SQLException {
    long id = insertValidPackage("状态约束包");

    assertThatThrownBy(
            () -> execute("UPDATE token_package SET status = 9 WHERE id = " + id))
        .as("the CHECK constraint must reject a status outside OFF_SHELF/ON_SALE")
        .isInstanceOf(SQLException.class);
  }

  @Test
  void shouldRejectNonPositiveCreditAmountAndPrice() {
    assertThatThrownBy(() -> insertRaw("零额度", 0L, 100L))
        .as("credit_amount must be greater than zero")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insertRaw("零价格", 100L, 0L))
        .as("list_price_cent must be greater than zero")
        .isInstanceOf(SQLException.class);

    assertThatThrownBy(() -> insertRaw("负额度", -1L, 100L))
        .as("the unsigned column type must reject a negative amount")
        .isInstanceOf(Exception.class);
  }

  @Test
  void shouldStoreNamesInTheSharedCollationAndAmountsAsUnsignedBigints() throws SQLException {
    assertThat(columnDefinition("token_package", "name"))
        .isEqualTo("varchar(96)");
    assertThat(columnDefinition("token_package", "credit_amount")).isEqualTo("bigint unsigned");
    assertThat(columnDefinition("token_package", "list_price_cent")).isEqualTo("bigint unsigned");
    assertThat(columnDefinition("token_package", "status")).isEqualTo("tinyint");
    assertThat(columnDefinition("token_package", "created_at")).isEqualTo("datetime(3)");
    assertThat(columnDefinition("token_package", "updated_at")).isEqualTo("datetime(3)");
    assertThat(columnCollation("token_package", "name")).isEqualTo("utf8mb4_0900_ai_ci");
  }

  @Test
  void shouldRoundTripStatusAsNumericCode() throws SQLException {
    TokenPackage tokenPackage = newPackage("枚举包", PackageStatus.ON_SALE);
    packageMapper.insert(tokenPackage);
    createdIds.add(tokenPackage.getId());

    assertThat(packageMapper.selectById(tokenPackage.getId()).getStatus())
        .isEqualTo(PackageStatus.ON_SALE);
    assertThat(rawStatus(tokenPackage.getId())).as("ON_SALE is stored as 1").isEqualTo(1);
  }

  @Test
  void shouldAllowTwoPackagesToShareADisplayName() {
    TokenPackage first = newPackage("同名套餐", PackageStatus.OFF_SHELF);
    TokenPackage second = newPackage("同名套餐", PackageStatus.OFF_SHELF);

    packageMapper.insert(first);
    createdIds.add(first.getId());
    packageMapper.insert(second);
    createdIds.add(second.getId());

    assertThat(second.getId()).isNotEqualTo(first.getId());
  }

  private long insertValidPackage(String name) throws SQLException {
    TokenPackage tokenPackage = newPackage(name, PackageStatus.OFF_SHELF);
    packageMapper.insert(tokenPackage);
    createdIds.add(tokenPackage.getId());
    return tokenPackage.getId();
  }

  private TokenPackage newPackage(String name, PackageStatus status) {
    TokenPackage tokenPackage = new TokenPackage();
    tokenPackage.setName(name);
    tokenPackage.setCreditAmount(1000L);
    tokenPackage.setListPriceCent(999L);
    tokenPackage.setStatus(status);
    return tokenPackage;
  }

  /** Inserts through SQL so the failure comes from the database, not from the API validation. */
  private void insertRaw(String name, long creditAmount, long listPriceCent) throws SQLException {
    execute(
        "INSERT INTO token_package(name, credit_amount, list_price_cent, status) VALUES ('"
            + name
            + "', "
            + creditAmount
            + ", "
            + listPriceCent
            + ", 0)");
  }

  private int rawStatus(long id) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement();
        ResultSet rs =
            statement.executeQuery("SELECT status FROM token_package WHERE id = " + id)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private List<String> indexNames(String table) throws SQLException {
    return query(
        "SELECT DISTINCT index_name FROM information_schema.statistics "
            + "WHERE table_schema = DATABASE() AND table_name = '"
            + table
            + "'");
  }

  private List<String> checkClauses(String table) throws SQLException {
    return query(
        "SELECT cc.check_clause FROM information_schema.check_constraints cc "
            + "JOIN information_schema.table_constraints tc "
            + "  ON tc.constraint_schema = cc.constraint_schema "
            + " AND tc.constraint_name = cc.constraint_name "
            + "WHERE cc.constraint_schema = DATABASE() AND tc.table_name = '"
            + table
            + "'");
  }

  private String columnDefinition(String table, String column) throws SQLException {
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

  private String columnCollation(String table, String column) throws SQLException {
    return query(
            "SELECT collation_name FROM information_schema.columns "
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

  /** Runs a statement that is expected to be rejected by the database. */
  private void execute(String sql) throws SQLException {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }
}
