import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ApiError } from '../api/http';
import { fetchOrder, type OrderDetail, type OrderStatus } from '../api/orders';
import { useAuth } from '../auth/useAuth';
import { formatInstant, formatPrice } from '../lib/format';

const STATUS_LABEL: Record<OrderStatus, string> = {
  UNPAID: '待支付',
  PAID: '已支付',
  CLOSED: '已关闭',
};

/**
 * Order detail.
 *
 * <p>Everything on this page comes from the order the server stored: the amounts are the snapshots
 * taken at creation time, the status is the server's state, and the deadline is the instant the
 * server fixed. Nothing is recomputed from the browser clock — a countdown would be a second,
 * disagreeing source of truth, and this stage has no payment yet.
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

  return (
    <main className="card wide">
      <nav className="topnav">
        <Link to="/products">套餐</Link>
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

          <p className="hint">支付功能将在后续版本开放，本页仅展示服务端记录的订单信息。</p>
        </>
      ) : null}
    </main>
  );
}
