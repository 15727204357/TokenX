package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.auth.model.UserRole;
import com.tokenx.auth.model.UserStatus;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
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
import org.springframework.dao.DuplicateKeyException;

/**
 * Schema guarantees the API cannot show: the unique index is case-insensitive, the enum checks
 * exist, and the account row is keyed by user.
 */
@SpringBootTest
class AuthSchemaTest extends AbstractIntegrationTest {

  @Autowired private AppUserMapper userMapper;
  @Autowired private TokenAccountMapper accountMapper;
  @Autowired private DataSource dataSource;

  private final List<Long> createdUserIds = new ArrayList<>();

  @AfterEach
  void deleteCreatedRows() {
    for (Long userId : createdUserIds) {
      accountMapper.deleteById(userId);
      userMapper.deleteById(userId);
    }
    createdUserIds.clear();
  }

  @Test
  void shouldTreatUpperAndLowerCaseUsernamesAsTheSameAccount() {
    AppUser first = newUser("Alice" + TestAccounts.shortSuffix());
    userMapper.insert(first);
    createdUserIds.add(first.getId());

    AppUser caseVariant = newUser(first.getUsername().toLowerCase());

    // The unique index, not a pre-check query, rejects the case variant.
    assertThatThrownBy(() -> userMapper.insert(caseVariant))
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  void shouldDeclareUniqueIndexAndEnumChecksOnAppUser() throws SQLException {
    assertThat(indexNames("app_user")).contains("uk_user_username");

    String clauses = String.join("\n", checkClauses("app_user"));
    assertThat(clauses).contains("role").contains("status");
  }

  @Test
  void shouldRejectUnknownRoleValueAtTheDatabaseLevel() {
    AppUser user = newUser(TestAccounts.uniqueUsername("check"));
    userMapper.insert(user);
    createdUserIds.add(user.getId());

    assertThatThrownBy(
            () -> execute("UPDATE app_user SET role = 9 WHERE id = " + user.getId()))
        .as("the CHECK constraint must reject a role outside USER/ADMIN")
        .isInstanceOf(java.sql.SQLException.class);
  }

  @Test
  void shouldUseCaseInsensitiveCollationForUsername() throws SQLException {
    assertThat(columnCollation("app_user", "username")).isEqualTo("utf8mb4_0900_ai_ci");
  }

  @Test
  void shouldKeyTokenAccountByUserId() throws SQLException {
    assertThat(primaryKeyColumns("token_account")).containsExactly("user_id");
  }

  @Test
  void shouldRoundTripEnumsAsNumericCodes() {
    AppUser user = newUser(TestAccounts.uniqueUsername("enum"));
    userMapper.insert(user);
    createdUserIds.add(user.getId());

    AppUser loaded = userMapper.selectById(user.getId());

    assertThat(loaded.getRole()).isEqualTo(UserRole.USER);
    assertThat(loaded.getStatus()).isEqualTo(UserStatus.ACTIVE);
  }

  @Test
  void shouldRejectNegativeBalanceAtTheColumnType() {
    AppUser user = newUser(TestAccounts.uniqueUsername("acct"));
    userMapper.insert(user);
    createdUserIds.add(user.getId());

    TokenAccount account = new TokenAccount();
    account.setUserId(user.getId());
    account.setAvailableBalance(-1L);

    assertThatThrownBy(() -> accountMapper.insert(account))
        .as("BIGINT UNSIGNED must make a negative balance impossible")
        .isInstanceOf(Exception.class);
  }

  private AppUser newUser(String username) {
    AppUser user = new AppUser();
    user.setUsername(username);
    user.setPasswordHash("$2a$10$notarealhash");
    user.setRole(UserRole.USER);
    user.setStatus(UserStatus.ACTIVE);
    return user;
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

  private String columnCollation(String table, String column) throws SQLException {
    List<String> result =
        query(
            "SELECT collation_name FROM information_schema.columns "
                + "WHERE table_schema = DATABASE() AND table_name = '"
                + table
                + "' AND column_name = '"
                + column
                + "'");
    return result.isEmpty() ? null : result.get(0);
  }

  private List<String> primaryKeyColumns(String table) throws SQLException {
    return query(
        "SELECT column_name FROM information_schema.key_column_usage "
            + "WHERE table_schema = DATABASE() AND table_name = '"
            + table
            + "' AND constraint_name = 'PRIMARY' ORDER BY ordinal_position");
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
