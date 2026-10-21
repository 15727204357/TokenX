package com.tokenx.wallet.controller;

import com.tokenx.infrastructure.security.CurrentUser;
import com.tokenx.wallet.dto.LedgerPageResponse;
import com.tokenx.wallet.dto.WalletBalanceResponse;
import com.tokenx.wallet.service.WalletService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own wallet.
 *
 * <p>There is no user id in either path or query. The identity is taken from the verified token and
 * handed to the service as an argument, so "look at somebody else's wallet" is not an operation this
 * API can express — which is stronger than validating a parameter that should not exist.
 */
@RestController
@RequestMapping("/api/v1/wallet")
public class WalletController {

  private final WalletService walletService;

  public WalletController(WalletService walletService) {
    this.walletService = walletService;
  }

  @GetMapping
  public WalletBalanceResponse balance(@AuthenticationPrincipal Jwt jwt) {
    return walletService.balance(CurrentUser.from(jwt).id());
  }

  /**
   * @param cursor last entry id of the previous page; omit it for the newest page
   * @param limit page size, defaults to {@value WalletService#DEFAULT_PAGE_SIZE} and is capped at
   *     {@value WalletService#MAX_PAGE_SIZE}
   */
  @GetMapping("/ledger")
  public LedgerPageResponse ledger(
      @RequestParam(required = false) @Positive(message = "cursor must be a positive id") Long cursor,
      @RequestParam(required = false)
          @Min(value = 1, message = "limit must be between 1 and " + WalletService.MAX_PAGE_SIZE)
          @Max(
              value = WalletService.MAX_PAGE_SIZE,
              message = "limit must be between 1 and " + WalletService.MAX_PAGE_SIZE)
          Integer limit,
      @AuthenticationPrincipal Jwt jwt) {
    int size = limit == null ? WalletService.DEFAULT_PAGE_SIZE : limit;
    return walletService.ledger(CurrentUser.from(jwt).id(), cursor, size);
  }
}
