import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { fetchActivity, type ActivityDetail, type ActivityRuntimeStatus } from '../api/activities';
import { ApiError } from '../api/http';
import { createSeckillOrder } from '../api/orders';
import { useAuth } from '../auth/useAuth';
import { formatInstant, formatPrice } from '../lib/format';
import { newIdempotencyKey } from '../lib/idempotency';

const RUNTIME_LABEL: Record<ActivityRuntimeStatus, string> = {
  UPCOMING: '即将开始',
  ACTIVE: '进行中',
  ENDED: '已结束',
};

/**
 * Why the purchase button is disabled, in the order the reasons are checked.
 *
 * <p>An ended activity outranks a sold-out one: telling a visitor "sold out" about a sale that is
 * already over would be true but unhelpful.
 */
function disabledLabel(activity: ActivityDetail): string {
  if (activity.runtimeStatus === 'ENDED') {
    return '活动已结束';
  }
  if (activity.runtimeStatus === 'UPCOMING') {
    return '即将开始';
  }
  return '已售罄';
}

/** The button never lies about why it cannot be used: every disabled state names its reason. */
function actionLabel(activity: ActivityDetail, purchasing: boolean): string {
  if (purchasing) {
    return '正在下单…';
  }
  if (activity.runtimeStatus === 'ACTIVE' && !activity.soldOut) {
    return '立即抢购';
  }
  return disabledLabel(activity);
}

function purchaseMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'ACTIVITY_SOLD_OUT':
        return '手慢了，活动已被抢光';
      case 'ALREADY_PURCHASED':
        return '每人限购 1 份，你已购买过该活动';
      case 'ACTIVITY_NOT_ACTIVE':
        return '活动当前不可下单';
      case 'ACTIVITY_NOT_FOUND':
        return '活动不存在或尚未开放';
      case 'IDEMPOTENCY_KEY_REUSED':
        return '该请求已用于另一活动，请刷新页面后重试';
      case 'UNAUTHORIZED':
        return '登录状态已失效，请重新登录';
      case 'VALIDATION_FAILED':
        return '请求参数有误，请刷新页面后重试';
      default:
        return '下单失败，请稍后重试';
    }
  }
  return '下单失败，请稍后重试';
}

/**
 * Whether a failure might have left an order behind on the server.
 *
 * <p>These are exactly the cases where the request may have been committed without the browser
 * seeing the response, so the next attempt must reuse the same idempotency key. A business rejection
 * (`409`, `400`) is a definite answer — nothing was created — so a retry is a genuinely new attempt
 * and gets a new key.
 */
function mightHaveReachedTheServer(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}

/**
 * Public activity detail with the purchase action.
 *
 * <p>The page shows the server's `runtimeStatus` and `soldOut` verbatim: whether the window has
 * started, ended, or run out of stock is a server decision, and re-deriving any of it from the
 * browser clock would disagree with the backend at exactly the moment it matters. For the same
 * reason the page never shows a price or a credit amount other than the ones the server sent.
 *
 * <p>The idempotency key lives in a ref, not in state: it belongs to the attempt in progress and
 * changing it must not re-render or re-fetch anything.
 */
export function ActivityPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const navigate = useNavigate();
  const [activity, setActivity] = useState<ActivityDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [purchasing, setPurchasing] = useState(false);
  const [purchaseError, setPurchaseError] = useState<string | null>(null);
  const idempotencyKey = useRef<string | null>(null);

  useEffect(() => {
    if (!id) {
      setNotFound(true);
      setLoading(false);
      return;
    }

    let cancelled = false;
    setLoading(true);
    setError(null);
    setNotFound(false);

    fetchActivity(id)
      .then((detail) => {
        if (!cancelled) {
          setActivity(detail);
        }
      })
      .catch((cause: unknown) => {
        if (cancelled) {
          return;
        }
        setActivity(null);
        if (cause instanceof ApiError && cause.status === 404) {
          setNotFound(true);
        } else {
          setError('活动加载失败，请稍后重试');
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
  }, [id, attempt]);

  async function purchase() {
    if (!activity) {
      return;
    }
    if (!user) {
      navigate('/login');
      return;
    }

    setPurchaseError(null);
    setPurchasing(true);
    const key = idempotencyKey.current ?? newIdempotencyKey();
    idempotencyKey.current = key;

    try {
      const order = await createSeckillOrder(activity.id, key);
      idempotencyKey.current = null;
      navigate(`/orders/${order.orderNo}`);
    } catch (cause) {
      if (mightHaveReachedTheServer(cause)) {
        // Keep the key: the order may exist, and resending the key is what makes the retry safe.
        setPurchaseError('网络异常，请重试（重试不会重复下单）');
      } else {
        idempotencyKey.current = null;
        setPurchaseError(purchaseMessage(cause));
      }
    } finally {
      setPurchasing(false);
    }
  }

  const purchasable =
    activity !== null && activity.runtimeStatus === 'ACTIVE' && !activity.soldOut && !purchasing;

  return (
    <main className="card wide">
      <nav className="topnav">
        <Link to="/products">套餐</Link>
        <Link to={user ? '/' : '/login'}>{user ? '我的账号' : '登录'}</Link>
      </nav>

      <h1>秒杀活动</h1>

      {loading ? <p>正在加载活动…</p> : null}

      {!loading && notFound ? (
        <>
          <p className="error" role="alert">
            活动不存在或尚未开放
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

      {!loading && !notFound && !error && activity ? (
        <>
          <p className="badges">
            <span className={`badge runtime-${activity.runtimeStatus.toLowerCase()}`}>
              {RUNTIME_LABEL[activity.runtimeStatus]}
            </span>
            {activity.soldOut ? <span className="badge sold-out">已售罄</span> : null}
          </p>

          <dl>
            <dt>活动价</dt>
            <dd className="activity-price">{formatPrice(activity.activityPriceCent)}</dd>
            <dt>到账额度</dt>
            <dd>{activity.creditAmount} Token</dd>
            <dt>开始时间</dt>
            <dd>{formatInstant(activity.startAt)}</dd>
            <dt>结束时间</dt>
            <dd>{formatInstant(activity.endAt)}</dd>
            <dt>可用库存</dt>
            <dd>
              {activity.availableStock} / {activity.totalStock}
            </dd>
            <dt>每人限购</dt>
            <dd>{activity.purchaseLimit} 份</dd>
          </dl>

          <div className="actions">
            <button
              type="button"
              disabled={!purchasable}
              onClick={() => void purchase()}
              aria-busy={purchasing}
            >
              {actionLabel(activity, purchasing)}
            </button>
          </div>

          {purchaseError ? (
            <p className="error" role="alert">
              {purchaseError}
            </p>
          ) : null}

          <p className="hint">下单后请在订单页查看应付金额与支付截止时间。重复点击不会重复下单。</p>
        </>
      ) : null}
    </main>
  );
}
