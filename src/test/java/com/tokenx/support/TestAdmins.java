package com.tokenx.support;

import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.UserRole;
import com.tokenx.auth.model.UserStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Creates an administrator row directly in the database.
 *
 * <p>No public API may grant the {@code ADMIN} role, so a test that needs admin rights inserts the
 * row itself and then logs in through the normal endpoint. The token it receives is therefore
 * genuine and its role claim comes from the same code path production uses.
 */
public final class TestAdmins {

  private TestAdmins() {}

  public static Admin create(AppUserMapper userMapper, PasswordEncoder passwordEncoder) {
    String username = TestAccounts.uniqueUsername("admin");
    String password = TestAccounts.password();

    AppUser admin = new AppUser();
    admin.setUsername(username);
    admin.setPasswordHash(passwordEncoder.encode(password));
    admin.setRole(UserRole.ADMIN);
    admin.setStatus(UserStatus.ACTIVE);
    userMapper.insert(admin);

    return new Admin(admin.getId(), username, password);
  }

  /** Credentials of the created administrator, plus its id so the test can delete it again. */
  public record Admin(Long id, String username, String password) {}
}
