package com.tokenx.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.auth.dto.RegisterRequest;
import com.tokenx.auth.mapper.AppUserMapper;
import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.AppUser;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.auth.service.AuthService;
import com.tokenx.support.AbstractIntegrationTest;
import com.tokenx.support.TestAccounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the registration transaction boundary on real MySQL.
 *
 * <p>The failure is injected at the mapper boundary because there is no business rule that can make
 * the account insert fail; the rollback itself is then observed with a real query, so this does not
 * fake database behaviour.
 */
@SpringBootTest
class AuthRollbackTest extends AbstractIntegrationTest {

  @MockitoBean private TokenAccountMapper accountMapper;
  @Autowired private AppUserMapper userMapper;
  @Autowired private AuthService authService;

  @Test
  void shouldRollBackTheUserWhenTheAccountInsertFails() {
    given(accountMapper.insert(any(TokenAccount.class)))
        .willThrow(new DataIntegrityViolationException("injected account failure"));

    String username = TestAccounts.uniqueUsername("rollback");

    assertThatThrownBy(
            () -> authService.register(new RegisterRequest(username, TestAccounts.password())))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThat(
            userMapper.selectCount(
                Wrappers.<AppUser>lambdaQuery().eq(AppUser::getUsername, username)))
        .as("the user row must not survive a failed account insert")
        .isZero();
  }
}
