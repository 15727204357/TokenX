import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/http';
import { fetchOrder, mockPayOrder, type OrderDetail, type OrderStatus } from '../api/orders';
import { useAuth } from '../auth/useAuth';
import { formatInstant, formatPrice } from '../lib/format';

const STATUS_LABEL: Record<OrderStatus, string> = {
  UNPAID: '待支付',
  PAID: '已支付',
  CLOSED: '已关闭',
};

/**
 * Why a payment was refused, in words the buyer can act on.
 *
 * <p>`ORDER_NOT_FOUND` covers "no such order" and "someone else's order" with one message, because
 * the server deliberately makes those two cases indistinguishable.
 */
function paymentMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'ORDER_NOT_FOUND':
        return '订单不存在或无权查看';
      case 'ORDER_STATE_CONFLICT':
        return '订单已过期或已关闭，无法支付';
      case 'UNAUTHORIZED':
        return '登录状态已失效，请重新登录';
      case 'VALIDATION_FAILED':
        return '订单号无效，请返回订单列表重试';
      case 'NETWORK_ERROR':
        return '网络异常，支付结果未确认，请重试（重复支付不会重复入账）';
      default:
        return '支付失败，请稍后重试';
    }
  }
  return '支付失败，请稍后重试';
}

/**
 * Why a refresh found nothing newer, in words the buyer can act on.
 *
 * <p>The page does not guess a state from a failure: whatever the error says, the order already on
 * screen stays exactly as the server last reported it.
 */
function refreshMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'ORDER_NOT_FOUND':
        return '订单不存在或无权查看';
      case 'UNAUTHORIZED':
        return '登录状态已失效，请重新登录';
      case 'NETWORK_ERROR':
        return '网络异常，未能取得最新状态，请重试';
      default:
        return '刷新失败，请稍后重试';
    }
  }
  return '刷新失败，请稍后重试';
}

/**
 * Order detail, with the simulated payment action.
 *
 * <p>Everything on this page comes from the order the server stored: the amounts are the snapshots
 * taken at creation time, the status is the server's state, and the deadline is the instant the
 * server fixed. Nothing is recomputed from the browser clock — in particular the page never decides
 * on its own that an order has expired, because the server is the only authority on that and would
 * answer `409`. An expired order becomes `CLOSED` when the server's timeout scan runs, so the way to
 * see that is to ask the server again, which is what the refresh action does.
 *
 * <p>The payment button exists only for `UNPAID`. While a request is in flight it is disabled, so a
 * double click cannot become two calls; and when the server answers, the returned order — not an
 * assumption — becomes the state this page renders. `PAID` and `CLOSED` therefore never offer a
 * button, without any extra rule.
 *
 * <p>There is no polling and no countdown: a refresh happens when the buyer asks for one, and a
 * failed refresh keeps the previous order rather than inventing a closed one.
 *
 * <p>A missing order and another buyer's order are the same `404` by design, so there is no
 * "permission denied" branch to leak the difference.
 */
export function OrderPage() {
  const { orderNo } = useParams<{ orderNo: string }>();
  const { user } = useAuth();
  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [paying, setPaying] = useState(false);
  const [payError, setPayError] = useState<string | null>(null);
  const [paidNotice, setPaidNotice] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  useEffect(() => {
    if (!orderNo) {
      setNotFound(true);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);
    setNotFound(false);

    fetchOrder(orderNo)
      .then((detail) => {
        if (!cancelled) {
          setOrder(detail);
        }
      })
      .catch((cause: unknown) => {
        if (cancelled) {
          return;
        }
        setOrder(null);
        if (cause instanceof ApiError && cause.status === 404) {
          setNotFound(true);
        } else {
          setError('订单加载失败，请稍后重试');
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
  }, [orderNo, attempt]);

  async function pay() {
    if (!order || paying) {
      // A second click while one request is in flight is not a second payment.
      return;
    }

    setPaying(true);
    setPayError(null);
    setPaidNotice(null);

    try {
      const updated = await mockPayOrder(order.orderNo);
      setOrder(updated);
      if (updated.status === 'PAID') {
        setPaidNotice('支付成功，Token 已入账');
      }
    } catch (cause) {
      setPayError(paymentMessage(cause));
    } finally {
      setPaying(false);
    }
  }

  async function refresh() {
    if (!order || refreshing || paying) {
      // A second click while one request is in flight would only re-read the same row.
      return;
    }

    setRefreshing(true);
    setRefreshError(null);

    try {
      const latest = await fetchOrder(order.orderNo);
      // The response is the state: if the timeout scan has closed the order since it was loaded,
      // this is where the page learns it, and the badge and closedAt follow from that object.
      setOrder(latest);
      setPaidNotice(null);
      setPayError(null);
    } catch (cause) {
      setRefreshError(refreshMessage(cause));
    } finally {
      setRefreshing(false);
    }
  }

  return (
    <main className="card wide">
      <nav className="topnav">
        <Link to="/products">套餐</Link>
        <Link to="/wallet">钱包</Link>
        <Link to={user ? '/' : '/login'}>{user ? '我的账号' : '登录'}</Link>
      </nav>

      <h1>订单详情</h1>

      {loading ? <p>正在加载订单…</p> : null}

      {!loading && notFound ? (
        <>
          <p className="error" role="alert">
            订单不存在或无权查看
          </p>
          <p className="hint">
            <Link to="/products">返回套餐列表</Link>
          </p>
        </>
      ) : null}

      {!loading && !notFound && error ? (
        <>
          <p className="error" role="alert">
            {error}
          </p>
          <button type="button" onClick={() => setAttempt((value) => value + 1)}>
            重试
          </button>
        </>
      ) : null}

      {!loading && !notFound && !error && order ? (
        <>
          <p className="badges">
            <span className={`badge order-${order.status.toLowerCase()}`}>
              {STATUS_LABEL[order.status]}
            </span>
          </p>

          <dl>
            <dt>订单号</dt>
            <dd className="order-no">{order.orderNo}</dd>
            <dt>套餐</dt>
            <dd>{order.packageName}</dd>
            <dt>到账额度</dt>
            <dd>{order.creditAmount} Token</dd>
            <dt>应付金额</dt>
            <dd className="activity-price">{formatPrice(order.payAmountCent)}</dd>
            <dt>下单时间</dt>
            <dd>{formatInstant(order.createdAt)}</dd>
            <dt>支付截止</dt>
            <dd>{formatInstant(order.expiresAt)}</dd>
            {order.paidAt ? (
              <>
                <dt>支付时间</dt>
                <dd>{formatInstant(order.paidAt)}</dd>
              </>
            ) : null}
            {order.closedAt ? (
              <>
                <dt>关闭时间</dt>
                <dd>{formatInstant(order.closedAt)}</dd>
              </>
            ) : null}
          </dl>

          <div className="actions">
            {order.status === 'UNPAID' ? (
              <button type="button" onClick={() => void pay()} disabled={paying} aria-busy={paying}>
                {paying ? '正在支付…' : '模拟支付'}
              </button>
            ) : null}
            <button
              type="button"
              onClick={() => void refresh()}
              disabled={refreshing || paying}
              aria-busy={refreshing}
            >
              {refreshing ? '正在刷新…' : '刷新订单状态'}
            </button>
          </div>

          {refreshError ? (
            <p className="error" role="alert">
              {refreshError}
            </p>
          ) : null}

          {payError ? (
            <p className="error" role="alert">
              {payError}
            </p>
          ) : null}

          {paidNotice ? (
            <p className="notice">
              {paidNotice}，<Link to="/wallet">查看钱包与流水</Link>
            </p>
          ) : null}

          <p className="hint">
            这是本地模拟支付，不会发生真实扣款。支付结果与到账额度都以服务端返回为准。
          </p>
        </>
      ) : null}
    </main>
  );
}
