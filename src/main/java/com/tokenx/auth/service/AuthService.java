package com.tokenx.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.dto.LoginRequest;
import com.tokenx.auth.dto.RegisterRequest;
import com.tokenx.auth.dto.TokenResponse;
import com.tokenx.auth.dto.UserResponse;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.auth.model.UserRole;
import com.tokenx.auth.model.UserStatus;
import com.tokenx.infrastructure.error.BusinessException;
import com.tokenx.infrastructure.security.JwtTokenIssuer;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration and login rules.
 *
 * <p>Registration writes the user and its zero-balance account in one transaction, so a failure in
 * either insert leaves no half-created account behind. Uniqueness is decided by the database index,
 * never by a "select then insert" check: under concurrent registration the second insert fails and
 * is translated into the same stable error that a sequential duplicate gets.
 */
@Service
public class AuthService {

  static final String USERNAME_ALREADY_EXISTS = "USERNAME_ALREADY_EXISTS";
  static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
  static final String ACCOUNT_DISABLED = "ACCOUNT_DISABLED";

  private final AppUserMapper userMapper;
  private final TokenAccountMapper accountMapper;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenIssuer tokenIssuer;

  /**
   * A digest of a value nobody knows, verified when the username does not exist so that both
   * failure branches spend comparable time and cannot be told apart by timing.
   */
  private final String unknownUserHash;

  public AuthService(
      AppUserMapper userMapper,
      TokenAccountMapper accountMapper,
      PasswordEncoder passwordEncoder,
      JwtTokenIssuer tokenIssuer) {
    this.userMapper = userMapper;
    this.accountMapper = accountMapper;
    this.passwordEncoder = passwordEncoder;
    this.tokenIssuer = tokenIssuer;
    this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Transactional
  public UserResponse register(RegisterRequest request) {
    AppUser user = new AppUser();
    user.setUsername(request.username());
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setRole(UserRole.USER);
    user.setStatus(UserStatus.ACTIVE);

    try {
      userMapper.insert(user);
    } catch (DuplicateKeyException ex) {
      // The unique index, not an application pre-check, decides this — including concurrent attempts.
      throw new BusinessException(
          HttpStatus.CONFLICT, USERNAME_ALREADY_EXISTS, "username is already registered");
    }

    TokenAccount account = new TokenAccount();
    account.setUserId(user.getId());
    account.setAvailableBalance(0L);
    accountMapper.insert(account);

    return UserResponse.from(user);
  }

  /**
   * Verifies credentials and issues a token.
   *
   * <p>Unknown user and wrong password return the identical error, so the response cannot be used to
   * enumerate accounts. A disabled account is only reported after the password matched.
   */
  public TokenResponse login(LoginRequest request) {
    AppUser user =
        userMapper.selectOne(
            Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, request.username()));

    if (user == null) {
      passwordEncoder.matches(request.password(), unknownUserHash);
      throw invalidCredentials();
    }
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw invalidCredentials();
    }
    if (user.getStatus() == UserStatus.DISABLED) {
      throw new BusinessException(HttpStatus.FORBIDDEN, ACCOUNT_DISABLED, "account is disabled");
    }
    return tokenIssuer.issue(user);
  }

  private static BusinessException invalidCredentials() {
    return new BusinessException(
        HttpStatus.UNAUTHORIZED, INVALID_CREDENTIALS, "username or password is incorrect");
  }
}
