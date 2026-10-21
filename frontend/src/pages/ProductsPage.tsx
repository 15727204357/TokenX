import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { fetchProducts, type Product } from '../api/products';
import { useAuth } from '../auth/useAuth';

/** Integer cents rendered as "¥12.34". Display only: the stored value stays an integer. */
function formatPrice(cent: number): string {
  const yuan = Math.floor(cent / 100);
  const remainder = cent % 100;
  return `¥${yuan}.${String(remainder).padStart(2, '0')}`;
}

/**
 * Public package list. Reachable without logging in; the server only ever returns on-sale packages.
 *
 * <p>Paging follows the server's `nextCursor` instead of computing an offset locally, so a page can
 * never skip or repeat a row because the client guessed wrong.
 */
export function ProductsPage() {
  const { user } = useAuth();
  const [products, setProducts] = useState<Product[]>([]);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
            </li>
          ))}
        </ul>
      ) : null}

      {products.length > 0 && error ? (
        <p className="error" role="alert">
          {error}
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
