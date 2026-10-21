import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '../api/http';
import { createNormalOrder } from '../api/orders';
import { fetchProducts, type Product } from '../api/products';
import { useAuth } from '../auth/useAuth';
import { formatPrice } from '../lib/format';
import { newIdempotencyKey } from '../lib/idempotency';
import { mightHaveReachedTheServer } from '../lib/purchase';

/** Why a normal purchase was refused, in the words a buyer can act on. */
function purchaseMessage(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.code) {
      case 'PACKAGE_NOT_AVAILABLE':
        return '该套餐已下架，无法购买';
      case 'PRODUCT_NOT_FOUND':
        return '套餐不存在或已下架';
      case 'IDEMPOTENCY_KEY_REUSED':
        return '该请求已用于其他套餐，请刷新页面后重试';
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
 * Public package list, with a direct purchase action.
 *
 * <p>Reachable without logging in; the server only ever returns on-sale packages. Paging follows the
 * server's `nextCursor` instead of computing an offset locally, so a page can never skip or repeat a
 * row because the client guessed wrong.
 *
 * <p>Buying from this page is the normal channel: no activity, no stock, and a buyer may do it as
 * often as they like. The idempotency key is therefore kept per package, not per page. A key that
 * belongs to an attempt whose outcome is unknown (network error, timeout, 5xx) must be reused so the
 * retry cannot buy a second package, while a key that ended in a definite refusal is discarded. Keying
 * the map by package id is what keeps those two rules apart: switching to another package starts a new
 * attempt with a new key instead of replaying the first package's uncertainty.
 */
export function ProductsPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [products, setProducts] = useState<Product[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [purchasingId, setPurchasingId] = useState<number | null>(null);
  const [purchaseError, setPurchaseError] = useState<string | null>(null);
  const pendingKeys = useRef<Map<number, string>>(new Map());

  const loadFirstPage = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const page = await fetchProducts();
      setProducts(page.items);
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch {
      setError('套餐列表加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadFirstPage();
  }, [loadFirstPage]);

  const loadMore = useCallback(async () => {
    if (nextCursor === null) {
      return;
    }
    setLoadingMore(true);
    setError(null);
    try {
      const page = await fetchProducts(nextCursor);
      setProducts((current) => [...current, ...page.items]);
      setNextCursor(page.nextCursor);
      setHasMore(page.hasMore);
    } catch {
      setError('加载更多失败，请稍后重试');
    } finally {
      setLoadingMore(false);
    }
  }, [nextCursor]);

  async function purchase(product: Product) {
    if (!user) {
      navigate('/login');
      return;
    }
    if (purchasingId !== null) {
      // A second click while one request is in flight is not a second purchase.
      return;
    }

    setPurchaseError(null);
    setPurchasingId(product.id);

    const key = pendingKeys.current.get(product.id) ?? newIdempotencyKey();
    pendingKeys.current.set(product.id, key);

    try {
      const order = await createNormalOrder(product.id, key);
      pendingKeys.current.delete(product.id);
      navigate(`/orders/${order.orderNo}`);
    } catch (cause) {
      if (mightHaveReachedTheServer(cause)) {
        // Keep this package's key: the order may exist, and resending the key is what makes the
        // retry safe.
        setPurchaseError('网络异常，请重试（重试不会重复下单）');
      } else {
        pendingKeys.current.delete(product.id);
        setPurchaseError(purchaseMessage(cause));
      }
    } finally {
      setPurchasingId(null);
    }
  }

  const isEmpty = !loading && products.length === 0;

  return (
    <main className="card wide">
      <nav className="topnav">
        <Link to="/products">套餐</Link>
        <Link to={user ? '/' : '/login'}>{user ? '我的账号' : '登录'}</Link>
      </nav>

      <h1>Token 套餐</h1>

      {loading ? <p>正在加载套餐…</p> : null}

      {isEmpty && error ? (
        <>
          <p className="error" role="alert">
            {error}
          </p>
          <button type="button" onClick={() => void loadFirstPage()}>
            重试
          </button>
        </>
      ) : null}

      {isEmpty && !error ? <p>暂无可购买的套餐</p> : null}

      {products.length > 0 ? (
        <ul className="product-list">
          {products.map((product) => (
            <li key={product.id}>
              <span className="product-name">{product.name}</span>
              <span className="product-credit">{product.creditAmount} Token</span>
              <span className="product-price">{formatPrice(product.listPriceCent)}</span>
              <button
                type="button"
                disabled={purchasingId !== null}
                aria-busy={purchasingId === product.id}
                aria-label={`购买${product.name}`}
                onClick={() => void purchase(product)}
              >
                {purchasingId === product.id ? '正在下单…' : '立即购买'}
              </button>
            </li>
          ))}
        </ul>
      ) : null}

      {products.length > 0 && error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}

      {purchaseError ? (
        <p className="error" role="alert">
          {purchaseError}
        </p>
      ) : null}

      {hasMore ? (
        <button type="button" disabled={loadingMore} onClick={() => void loadMore()}>
          {loadingMore ? '正在加载…' : '加载更多'}
        </button>
      ) : null}
    </main>
  );
}
