import { http } from './http';

/** What a posting is about. Only `ORDER` exists in this stage. */
export type LedgerBizType = 'ORDER';

/** Which way a posting moved the balance. `DEBIT` is reserved and not produced yet. */
export type LedgerEffectType = 'CREDIT' | 'DEBIT';

/**
 * The caller's own balance.
 *
 * <p>There is no user id in this shape on purpose: the endpoint can only describe the account behind
 * the access token, so identifying a person again would be redundant at best.
 */
export interface WalletBalance {
  availableBalance: number;
  updatedAt: string;
}

/**
 * One posting, as the server records it.
 *
 * <p>`delta` is signed and matches `effectType`: a credit is positive. `balanceAfter` is the balance
 * at that moment, which is why the list can be read top-down without recomputing anything.
 */
export interface LedgerEntry {
  entryId: number;
  bizType: LedgerBizType;
  /** The public order number; null only if the order row it references is missing. */
  orderNo: string | null;
  effectType: LedgerEffectType;
  delta: number;
  balanceAfter: number;
  createdAt: string;
}

/** One page of postings, newest first. `nextCursor` is null exactly when nothing is left. */
export interface LedgerPage {
  items: LedgerEntry[];
  nextCursor: number | null;
  hasMore: boolean;
}

/** The current user's balance. */
export async function fetchWallet(): Promise<WalletBalance> {
  const { data } = await http.get<WalletBalance>('/wallet');
  return data;
}

/**
 * One page of the current user's postings.
 *
 * <p>The cursor is the last `entryId` of the page already shown, never an offset: a new payment
 * arriving between two requests must not shift the window and make a page repeat or skip an entry.
 *
 * @param cursor last entry id of the previous page; omit it for the newest page
 */
export async function fetchLedger(cursor?: number): Promise<LedgerPage> {
  const { data } = await http.get<LedgerPage>('/wallet/ledger', {
    params: cursor === undefined ? {} : { cursor },
  });
  return data;
}
