import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchMe } from '../api/auth';
import { ApiError } from '../api/http';
import { createNormalOrder, type OrderDetail } from '../api/orders';
import { fetchProducts } from '../api/products';
import { AuthProvider } from '../auth/AuthContext';
import { clearToken } from '../auth/storage';
import { ProductsPage } from './ProductsPage';

vi.mock('../api/products');
vi.mock('../api/orders');
vi.mock('../api/auth');

const sample = {
  id: 11,
  name: '入门包',
  creditAmount: 1000,
  listPriceCent: 990,
  status: 'ON_SALE' as const,
};

const other = { ...sample, id: 12, name: '专业包', creditAmount: 5000, listPriceCent: 100000 };

const sampleOrder: OrderDetail = {
  orderNo: 'ABCDEFGHJK0000000000000002',
  orderType: 'NORMAL',
  activityId: null,
  packageId: 11,
  packageName: '入门包',
  creditAmount: 1000,
  payAmountCent: 990,
  status: 'UNPAID',
  expiresAt: '2026-10-21T12:15:00Z',
  paidAt: null,
  closedAt: null,
  createdAt: '2026-10-21T12:00:00Z',
};

const CANONICAL_UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function OrderRouteMarker() {
  const { orderNo } = useParams<{ orderNo: string }>();
  return <p>订单页 {orderNo}</p>;
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={['/products']}>
      <AuthProvider>
        <Routes>
          <Route path="/products" element={<ProductsPage />} />
          <Route path="/login" element={<p>登录页标记</p>} />
          <Route path="/orders/:orderNo" element={<OrderRouteMarker />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

/** Puts the provider in a logged-in state: a stored token plus a confirmed `/users/me`. */
async function signInAsBuyer() {
  window.localStorage.setItem('tokenx.accessToken', 'stored-token');
  vi.mocked(fetchMe).mockResolvedValue({ id: 42, username: 'buyer', role: 'USER' });
}

describe('套餐列表', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
    clearToken();
  });

  it('展示套餐名称、Token 额度和格式化后的价格', async () => {
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample, other],
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

  it('匿名用户点击购买时跳转登录页并且不下单', async () => {
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample],
      nextCursor: null,
      hasMore: false,
    });

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '购买入门包' }));

    expect(await screen.findByText('登录页标记')).toBeInTheDocument();
    expect(createNormalOrder).not.toHaveBeenCalled();
  });

  it('登录用户购买时只发送 packageId 和规范 UUID，成功后跳转订单详情', async () => {
    await signInAsBuyer();
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample],
      nextCursor: null,
      hasMore: false,
    });
    vi.mocked(createNormalOrder).mockResolvedValue(sampleOrder);

    renderPage();

    expect(await screen.findByText('我的账号')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));

    expect(await screen.findByText(`订单页 ${sampleOrder.orderNo}`)).toBeInTheDocument();

    const [packageId, key] = vi.mocked(createNormalOrder).mock.calls[0];
    expect(packageId).toBe(11);
    expect(key).toMatch(CANONICAL_UUID);
  });

  it('网络失败后重试同一套餐复用同一个幂等键', async () => {
    await signInAsBuyer();
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample],
      nextCursor: null,
      hasMore: false,
    });
    vi.mocked(createNormalOrder)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'request failed'))
      .mockResolvedValueOnce(sampleOrder);

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络异常，请重试（重试不会重复下单）',
    );

    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));

    expect(await screen.findByText(`订单页 ${sampleOrder.orderNo}`)).toBeInTheDocument();

    const calls = vi.mocked(createNormalOrder).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1][1]).toBe(calls[0][1]);
  });

  it('明确的业务拒绝显示明确文案，并以新的幂等键重试', async () => {
    await signInAsBuyer();
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample],
      nextCursor: null,
      hasMore: false,
    });
    vi.mocked(createNormalOrder).mockRejectedValueOnce(
      new ApiError(409, 'PACKAGE_NOT_AVAILABLE', 'package is not on sale'),
    );

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('该套餐已下架，无法购买');

    vi.mocked(createNormalOrder).mockResolvedValueOnce(sampleOrder);
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));
    await screen.findByText(`订单页 ${sampleOrder.orderNo}`);

    const calls = vi.mocked(createNormalOrder).mock.calls;
    expect(calls[1][1]).not.toBe(calls[0][1]);
  });

  it('切换套餐时不复用另一个套餐未决请求的幂等键', async () => {
    await signInAsBuyer();
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample, other],
      nextCursor: null,
      hasMore: false,
    });
    vi.mocked(createNormalOrder)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce({
        ...sampleOrder,
        packageId: 12,
        orderNo: 'ABCDEFGHJK0000000000000003',
      });

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络异常，请重试（重试不会重复下单）',
    );

    await userEvent.click(screen.getByRole('button', { name: '购买专业包' }));
    await screen.findByText('订单页 ABCDEFGHJK0000000000000003');

    const calls = vi.mocked(createNormalOrder).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[0][0]).toBe(11);
    expect(calls[1][0]).toBe(12);
    expect(calls[1][1]).not.toBe(calls[0][1]);
  });

  it('请求进行中不会连续创建重复请求', async () => {
    await signInAsBuyer();
    vi.mocked(fetchProducts).mockResolvedValue({
      items: [sample, other],
      nextCursor: null,
      hasMore: false,
    });

    let resolveOrder: (order: OrderDetail) => void = () => {};
    vi.mocked(createNormalOrder).mockReturnValue(
      new Promise<OrderDetail>((resolve) => {
        resolveOrder = resolve;
      }),
    );

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));

    expect(screen.getByRole('button', { name: '购买入门包' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '购买专业包' })).toBeDisabled();

    await userEvent.click(screen.getByRole('button', { name: '购买入门包' }));
    await userEvent.click(screen.getByRole('button', { name: '购买专业包' }));

    expect(createNormalOrder).toHaveBeenCalledTimes(1);

    resolveOrder(sampleOrder);
    expect(await screen.findByText(`订单页 ${sampleOrder.orderNo}`)).toBeInTheDocument();
  });
});
