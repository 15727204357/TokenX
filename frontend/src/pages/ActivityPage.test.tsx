import { MemoryRouter, Route, Routes, useParams } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fetchActivity, type ActivityDetail } from '../api/activities';
import { fetchMe } from '../api/auth';
import { ApiError } from '../api/http';
import { createSeckillOrder, type OrderDetail } from '../api/orders';
import { AuthProvider } from '../auth/AuthContext';
import { ActivityPage } from './ActivityPage';

vi.mock('../api/activities');
vi.mock('../api/auth');
vi.mock('../api/orders');

const sample: ActivityDetail = {
  id: 7,
  packageId: 3,
  activityPriceCent: 799,
  creditAmount: 1000,
  startAt: '2026-10-22T02:00:00Z',
  endAt: '2026-10-22T03:00:00Z',
  runtimeStatus: 'UPCOMING',
  totalStock: 100,
  availableStock: 100,
  soldOut: false,
  purchaseLimit: 1,
};

const sampleOrder: OrderDetail = {
  orderNo: 'ABCDEFGHJK0000000000000001',
  orderType: 'SECKILL',
  activityId: 7,
  packageId: 3,
  packageName: '套餐A',
  creditAmount: 1000,
  payAmountCent: 799,
  status: 'UNPAID',
  expiresAt: '2026-10-22T02:15:00Z',
  paidAt: null,
  closedAt: null,
  createdAt: '2026-10-22T02:00:00Z',
};

function OrderRouteMarker() {
  const { orderNo } = useParams<{ orderNo: string }>();
  return <p>订单页 {orderNo}</p>;
}

function renderPage(id = '7') {
  return render(
    <MemoryRouter initialEntries={[`/activities/${id}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/activities/:id" element={<ActivityPage />} />
          <Route path="/login" element={<p>登录页标记</p>} />
          <Route path="/orders/:orderNo" element={<OrderRouteMarker />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

/**
 * Puts the provider in a logged-in state: a stored token plus a confirmed /users/me.
 *
 * <p>The token is written through `window.localStorage`, the very same jsdom storage that
 * `auth/storage.ts` reads, so the provider really does see the login state. A bare `localStorage`
 * resolves to the global binding, which is Node's own stub on Node 26 — see `src/test/setup.ts`.
 */
async function signInAsBuyer() {
  window.localStorage.setItem('tokenx.accessToken', 'stored-token');
  vi.mocked(fetchMe).mockResolvedValue({ id: 42, username: 'buyer', role: 'USER' });
}

describe('活动详情页', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    window.localStorage.clear();
  });

  it('展示即将开始的活动、价格、额度和 UTC 时间换算后的窗口', async () => {
    vi.mocked(fetchActivity).mockResolvedValue(sample);

    renderPage();

    // The badge and the disabled button both name the state, so the badge is selected explicitly.
    expect(await screen.findByText('即将开始', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '即将开始' })).toBeDisabled();
    expect(screen.getByText('¥7.99')).toBeInTheDocument();
    expect(screen.getByText('1000 Token')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:00:00')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 11:00:00')).toBeInTheDocument();
    expect(screen.getByText('100 / 100')).toBeInTheDocument();
    expect(screen.getByText('1 份')).toBeInTheDocument();
    expect(screen.queryByText('已售罄')).not.toBeInTheDocument();
    expect(fetchActivity).toHaveBeenCalledWith('7');
  });

  it('进行中且有库存时抢购按钮可以点击', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ACTIVE',
      availableStock: 42,
    });

    renderPage();

    const button = await screen.findByRole('button', { name: '立即抢购' });
    expect(button).toBeEnabled();
    expect(screen.getByText('42 / 100')).toBeInTheDocument();
  });

  it('即将开始时按钮不可点击并说明原因', async () => {
    vi.mocked(fetchActivity).mockResolvedValue(sample);

    renderPage();

    const button = await screen.findByRole('button', { name: '即将开始' });
    expect(button).toBeDisabled();
  });

  it('已结束时按钮不可点击并说明原因', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ENDED',
      availableStock: 0,
      soldOut: true,
    });

    renderPage();

    expect(await screen.findByText('已售罄')).toBeInTheDocument();
    const button = screen.getByRole('button', { name: '活动已结束' });
    expect(button).toBeDisabled();
  });

  it('库存为零时显示已售罄且按钮不可点击', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({
      ...sample,
      runtimeStatus: 'ACTIVE',
      availableStock: 0,
      soldOut: true,
    });

    renderPage();

    expect(await screen.findByText('已售罄', { selector: '.badge' })).toBeInTheDocument();
    expect(screen.getByText('进行中')).toBeInTheDocument();
    expect(screen.getByText('0 / 100')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '已售罄' })).toBeDisabled();
  });

  it('活动不存在时显示 404 文案而不是通用错误', async () => {
    vi.mocked(fetchActivity).mockRejectedValue(
      new ApiError(404, 'ACTIVITY_NOT_FOUND', 'activity not found'),
    );

    renderPage('999');

    expect(await screen.findByText('活动不存在或尚未开放')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
  });

  it('加载失败时显示错误并可重试', async () => {
    vi.mocked(fetchActivity)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce(sample);

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('活动加载失败，请稍后重试');

    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByRole('button', { name: '即将开始' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('匿名用户点击抢购时跳转登录页并且不下单', async () => {
    vi.mocked(fetchActivity).mockResolvedValue({ ...sample, runtimeStatus: 'ACTIVE' });

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '立即抢购' }));

    expect(await screen.findByText('登录页标记')).toBeInTheDocument();
    expect(createSeckillOrder).not.toHaveBeenCalled();
  });

  it('登录用户点击抢购后带上规范 UUID 并跳转订单详情', async () => {
    await signInAsBuyer();
    vi.mocked(fetchActivity).mockResolvedValue({ ...sample, runtimeStatus: 'ACTIVE' });
    vi.mocked(createSeckillOrder).mockResolvedValue(sampleOrder);

    renderPage();

    expect(await screen.findByText('我的账号')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }));

    expect(await screen.findByText(`订单页 ${sampleOrder.orderNo}`)).toBeInTheDocument();

    const [activityId, key] = vi.mocked(createSeckillOrder).mock.calls[0];
    expect(activityId).toBe(7);
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  });

  it('网络失败后重试复用同一个幂等键', async () => {
    await signInAsBuyer();
    vi.mocked(fetchActivity).mockResolvedValue({ ...sample, runtimeStatus: 'ACTIVE' });
    vi.mocked(createSeckillOrder)
      .mockRejectedValueOnce(new ApiError(0, 'NETWORK_ERROR', 'request failed'))
      .mockResolvedValueOnce(sampleOrder);

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络异常，请重试（重试不会重复下单）',
    );

    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }));

    expect(await screen.findByText(`订单页 ${sampleOrder.orderNo}`)).toBeInTheDocument();

    const calls = vi.mocked(createSeckillOrder).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls[1][1]).toBe(calls[0][1]);
  });

  it('业务拒绝显示明确文案，并允许用新的幂等键再次尝试', async () => {
    await signInAsBuyer();
    vi.mocked(fetchActivity).mockResolvedValue({ ...sample, runtimeStatus: 'ACTIVE' });
    vi.mocked(createSeckillOrder).mockRejectedValueOnce(
      new ApiError(409, 'ALREADY_PURCHASED', 'user already has an order for this activity'),
    );

    renderPage();

    await screen.findByText('我的账号');
    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('每人限购 1 份，你已购买过该活动');

    vi.mocked(createSeckillOrder).mockResolvedValueOnce(sampleOrder);
    await userEvent.click(screen.getByRole('button', { name: '立即抢购' }));
    await screen.findByText(`订单页 ${sampleOrder.orderNo}`);

    const calls = vi.mocked(createSeckillOrder).mock.calls;
    expect(calls[1][1]).not.toBe(calls[0][1]);
  });
});
