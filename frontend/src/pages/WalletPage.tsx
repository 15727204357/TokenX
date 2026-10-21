import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { fetchLedger, fetchWallet, type LedgerEntry, type WalletBalance } from '../api/wallet';
import { useAuth } from '../auth/useAuth';
import { formatInstant } from '../lib/format';

/**
 * The wallet: one balance and the postings that produced it.
 *
 * <p>Both are read from the server and neither is computed here. The balance is not the sum of the
 * rendered entries (the list may be paged, and a page is not the whole history), and an entry's
 * running balance is displayed as `balanceAfter` rather than recalculated — otherwise the page would
 * be a second, disagreeing ledger.
 *
 * <p>Reachable only when logged in: without a confirmed session the page redirects to `/login`
 * instead of rendering an empty wallet.
 */
export function WalletPage() {
  const { user, initializing } = useAuth();
  const [balance, setBalance] = useState<WalletBalance | null>(null);
  const [entries, setEntries] = useState<LedgerEntry[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!user) {
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);

    Promise.all([fetchWallet(), fetchLedger()])
      .then(([wallet, page]) => {
        if (cancelled) {
          return;
        }
        setBalance(wallet);
        setEntries(page.items);
        setNextCursor(page.nextCursor);
        setHasMore(page.hasMore);
      })
      .catch(() => {
        if (!cancelled) {
          setError('钱包加载失败，请稍后重试');
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [user, attempt]);

  const loadMore = useCallback(async () => {
    if (nextCursor === null || loadingMore) {
      return;
    }

    setLoadingMore(true);
    setError(null);
    try {
      const page = await fetchLedger(nextCursor);
      setEntries((current) => [...current, ...page.items]);
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch {
      setError('加载更多流水失败，请稍后重试');
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor, loadingMore]);

  if (initializing) {
    return (
      <main className="card wide">
        <p>正在加载…</p>
      </main>
    );
  }

  if (!user) {
    return <Navigate to="/login" replace />;
  }

  return (
    <main className="card wide">
      <nav className="topnav">
        <Link to="/products">套餐</Link>
        <Link to={user ? '/' : '/login'}>{user ? '我的账号' : '登录'}</Link>
      </nav>

      <h1>我的钱包</h1>

      {loading ? <p>正在加载钱包…</p> : null}

      {!loading && error && !balance ? (
        <>
          <p className="error" role="alert">
            {error}
          </p>
          <button type="button" onClick={() => setAttempt((value) => value + 1)}>
            重试
          </button>
        </>
      ) : null}

      {balance ? (
        <>
          <dl>
            <dt>可用额度</dt>
            <dd className="wallet-balance">{balance.availableBalance} Token</dd>
            <dt>更新时间</dt>
            <dd>{formatInstant(balance.updatedAt)}</dd>
          </dl>

          <h2>额度流水</h2>

          {entries.length === 0 ? (
            <p>暂无入账记录</p>
          ) : (
            <ul className="ledger-list">
              {entries.map((entry) => (
                <li key={entry.entryId}>
                  <span className="ledger-time">{formatInstant(entry.createdAt)}</span>
                  <span className="ledger-source">
                    {entry.orderNo ? (
                      <Link to={`/orders/${entry.orderNo}`}>{entry.orderNo}</Link>
                    ) : (
                      '—'
                    )}
                  </span>
                  <span
                    className={`ledger-delta ${entry.effectType === 'CREDIT' ? 'credit' : 'debit'}`}
                  >
                    {entry.delta > 0 ? `+${entry.delta}` : entry.delta}
                  </span>
                  <span className="ledger-balance">余额 {entry.balanceAfter}</span>
                </li>
              ))}
            </ul>
          )}

          {entries.length > 0 && error ? (
            <p className="error" role="alert">
              {error}
            </p>
          ) : null}

          {hasMore ? (
            <button type="button" disabled={loadingMore} onClick={() => void loadMore()}>
              {loadingMore ? '正在加载…' : '加载更多'}
            </button>
          ) : null}

          <p className="hint">流水由服务端记录，只增不改；额度以可用余额为准。</p>
        </>
      ) : null}
    </main>
  );
}
