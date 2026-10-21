import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { fetchActivity, type ActivityDetail, type ActivityRuntimeStatus } from '../api/activities';
import { ApiError } from '../api/http';
import { useAuth } from '../auth/useAuth';
import { formatInstant, formatPrice } from '../lib/format';

const RUNTIME_LABEL: Record<ActivityRuntimeStatus, string> = {
  UPCOMING: '即将开始',
  ACTIVE: '进行中',
  ENDED: '已结束',
};

/**
 * Public activity detail. Reachable without logging in.
 *
 * <p>The page shows the server's `runtimeStatus` verbatim: whether the window has started or ended is
 * a server decision, and re-deriving it from the browser clock would disagree with the backend at
 * exactly the moment it matters. The same holds for `soldOut`.
 *
 * <p>There is intentionally no purchase button in this stage: ordering arrives in F4.
 */
export function ActivityPage() {
  const { id } = useParams<{ id: string }>();
  const { user } = useAuth();
  const [activity, setActivity] = useState<ActivityDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attempt, setAttempt] = useState(0);

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

          <p className="hint">抢购功能尚未开放，本页仅展示活动信息。</p>
        </>
      ) : null}
    </main>
  );
}
