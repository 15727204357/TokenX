import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchProducts } from '../api/products';
import { AuthProvider } from '../auth/AuthContext';
import { clearToken } from '../auth/storage';
import { ProductsPage } from './ProductsPage';

vi.mock('../api/products');

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/products']}>
      <AuthProvider>
        <ProductsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

const sample = {
  id: 11,
  name: '入门包',
  creditAmount: 1000,
  listPriceCent: 990,
  status: 'ON_SALE' as const,
};

describe('套餐列表', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    clearToken();
  });

  it('展示套餐名称、Token 额度和格式化后的价格', async () => {
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [
        sample,
        { ...sample, id: 12, name: '专业包', creditAmount: 5000, listPriceCent: 100000 },
      ],
      nextCursor: null,
      hasMore: false,
    });

    renderPage();

    expect(await screen.findByText('入门包')).toBeInTheDocument();
    expect(screen.getByText('1000 Token')).toBeInTheDocument();
    expect(screen.getByText('¥9.90')).toBeInTheDocument();
    expect(screen.getByText('专业包')).toBeInTheDocument();
    expect(screen.getByText('¥1000.00')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument();
  });

  it('没有在售套餐时显示空状态', async () => {
    vi.mocked(fetchProducts).mockResolvedValue({ items: [], nextCursor: null, hasMore: false });

    renderPage();

    expect(await screen.findByText('暂无可购买的套餐')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('请求失败时显示错误并可重试', async () => {
    vi.mocked(fetchProducts)
      .mockRejectedValueOnce(new Error('network down'))
      .mockResolvedValueOnce({ items: [sample], nextCursor: null, hasMore: false });

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('套餐列表加载失败，请稍后重试');

    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('入门包')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('加载下一页时使用服务端返回的 nextCursor 并追加结果', async () => {
    vi.mocked(fetchProducts)
      .mockResolvedValueOnce({
        items: [sample],
        nextCursor: 11,
        hasMore: true,
      })
      .mockResolvedValueOnce({
        items: [{ ...sample, id: 10, name: '进阶包', creditAmount: 2000, listPriceCent: 1990 }],
        nextCursor: null,
        hasMore: false,
      });

    renderPage();

    expect(await screen.findByText('入门包')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '加载更多' }));

    expect(await screen.findByText('进阶包')).toBeInTheDocument();
    expect(fetchProducts).toHaveBeenNthCalledWith(1);
    expect(fetchProducts).toHaveBeenNthCalledWith(2, 11);
    expect(screen.getByText('入门包')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '加载更多' })).not.toBeInTheDocument();
  });
});
