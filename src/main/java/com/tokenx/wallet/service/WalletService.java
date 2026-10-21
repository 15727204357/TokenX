package com.tokenx.wallet.service;

import com.tokenx.auth.mapper.TokenAccountMapper;
import com.tokenx.auth.model.TokenAccount;
import com.tokenx.wallet.dto.LedgerEntryResponse;
import com.tokenx.wallet.dto.LedgerPageResponse;
import com.tokenx.wallet.dto.WalletBalanceResponse;
import com.tokenx.wallet.mapper.TokenLedgerMapper;
import com.tokenx.wallet.model.LedgerEntryRow;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Read side of the wallet. Both endpoints describe the caller and nobody else.
 *
 * <p>There is no "get wallet of user X" method on purpose: the user id is an argument supplied by
 * the controller from the verified token, and it is the only argument either method accepts that
 * could identify a person. A query parameter could not reach these methods even by accident.
 *
 * <p>Nothing here is transactional. Each call is one short read, and wrapping it in a transaction
 * would neither change what is returned nor make the two endpoints agree with each other — a balance
 * and a page of evidence are read at two different instants by definition.
 */
@Service
public class WalletService {

  /** Page size used when the caller does not ask for one. */
  public static final int DEFAULT_PAGE_SIZE = 20;

  /** Hard upper bound on a single page, so one request cannot ask for the whole history. */
  public static final int MAX_PAGE_SIZE = 100;

  private final TokenAccountMapper accountMapper;
  private final TokenLedgerMapper ledgerMapper;

  public WalletService(TokenAccountMapper accountMapper, TokenLedgerMapper ledgerMapper) {
    this.accountMapper = accountMapper;
    this.ledgerMapper = ledgerMapper;
  }

  /**
   * The caller's balance.
   *
   * <p>Registration creates the account row in the same transaction as the user (Q8), so a missing
   * row is a broken invariant rather than an empty wallet. It is reported as a server error instead
   * of being answered with a comforting zero — and it is never repaired here, because paying must
   * not create accounts and neither must reading one.
   */
  public WalletBalanceResponse balance(long userId) {
    TokenAccount account = accountMapper.selectById(userId);
    if (account == null) {
      throw new IllegalStateException(
          "user " + userId + " has no token_account row; registration must create it");
    }
    return WalletBalanceResponse.from(account);
  }

  /**
   * One page of the caller's postings, newest first.
   *
   * <p>{@code limit + 1} rows are requested only to learn whether a further page exists; the extra
   * row is dropped before the response is built.
   *
   * @param cursor last entry id of the previous page, {@code null} for the first page
   * @param limit requested page size; already validated to 1..{@value #MAX_PAGE_SIZE}
   */
  public LedgerPageResponse ledger(long userId, Long cursor, int limit) {
    int size = Math.min(limit, MAX_PAGE_SIZE);

    List<LedgerEntryRow> rows = ledgerMapper.selectPageByUser(userId, cursor, size + 1);

    boolean hasMore = rows.size() > size;
    List<LedgerEntryRow> page = hasMore ? rows.subList(0, size) : rows;
    Long nextCursor = hasMore ? page.get(page.size() - 1).getEntryId() : null;

    return new LedgerPageResponse(
        page.stream().map(LedgerEntryResponse::from).toList(), nextCursor, hasMore);
  }
}
