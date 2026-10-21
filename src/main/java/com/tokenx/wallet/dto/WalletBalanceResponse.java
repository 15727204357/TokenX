package com.tokenx.wallet.dto;

import com.tokenx.auth.model.TokenAccount;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * The caller's own balance.
 *
 * <p>Deliberately two fields: the balance and when it last changed. There is no user id in the
 * response, because the only account this endpoint can describe is the one the verified token
 * belongs to — echoing an id would suggest the endpoint could be pointed at somebody else.
 *
 * @param availableBalance spendable credits, an integer (never a float)
 * @param updatedAt when the account row last changed
 */
public record WalletBalanceResponse(Long availableBalance, Instant updatedAt) {

  public static WalletBalanceResponse from(TokenAccount account) {
    return new WalletBalanceResponse(
        account.getAvailableBalance(),
        account.getUpdatedAt() == null ? null : account.getUpdatedAt().toInstant(ZoneOffset.UTC));
  }
}
