import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { fetchOrder, mockPayOrder, type OrderDetail } from '../api/orders';
import { AuthProvider } from '../auth/AuthContext';
import { OrderPage } from './OrderPage';

vi.mock('../api/orders');

const sample: OrderDetail = {
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

const paid: OrderDetail = {
  ...sample,
  status: 'PAID',
  paidAt: '2026-10-22T02:05:00Z',
};

const closed: OrderDetail = {
  ...sample,
  status: 'CLOSED',
  closedAt: '2026-10-22T02:15:01Z',
};

function renderPage(orderNo = sample.orderNo) {
  return render(
    <MemoryRouter initialEntries={[`/orders/${orderNo}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/orders/:orderNo" element={<OrderPage />} />
          <Route path="/login" element={<p>登录页标记</p>} />
          <Route path="/wallet" element={<p>钱包页标记</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe('订单详情页', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    // window.localStorage, not the bare identifier: see ActivityPage.test.tsx.
    window.localStorage.clear();
  });

  it('展示服务端快照：单号、套餐、额度、金额与支付截止时间', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);

    renderPage();

    expect(await screen.findByText('待支付')).toBeInTheDocument();
    expect(screen.getByText(sample.orderNo)).toBeInTheDocument();
    expect(screen.getByText('套餐A')).toBeInTheDocument();
    expect(screen.getByText('1000 Token')).toBeInTheDocument();
    expect(screen.getByText('¥7.99')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:00:00')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:15:00')).toBeInTheDocument();
    expect(fetchOrder).toHaveBeenCalledWith(sample.orderNo);
  });

  it('待支付订单可以模拟支付，且不显示倒计时', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);

    renderPage();

    expect(await screen.findByRole('button', { name: '模拟支付' })).toBeInTheDocument();
    expect(screen.queryByText(/剩余/)).not.toBeInTheDocument();
    expect(screen.getByText(/本地模拟支付/)).toBeInTheDocument();
  });

  it('支付成功后以服务端响应刷新为已支付并展示支付时间', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);
    vi.mocked(mockPayOrder).mockResolvedValue(paid);

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '模拟支付' }));

    expect(await screen.findByText('已支付')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:05:00')).toBeInTheDocument();
    expect(screen.getByText(/支付成功/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '模拟支付' })).not.toBeInTheDocument();
    expect(mockPayOrder).toHaveBeenCalledWith(sample.orderNo);
    // The page was not re-fetched: the payment response is the new state.
    expect(fetchOrder).toHaveBeenCalledTimes(1);
  });

  it('请求进行中禁用按钮，连续点击只支付一次', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);
    let finishPayment!: (value: OrderDetail) => void;
    vi.mocked(mockPayOrder).mockReturnValue(
      new Promise<OrderDetail>((resolve) => {
        finishPayment = resolve;
      }),
    );

    renderPage();

    const button = await screen.findByRole('button', { name: '模拟支付' });
    await userEvent.click(button);
    await userEvent.click(button);

    expect(mockPayOrder).toHaveBeenCalledTimes(1);
    const pendingButton = screen.getByRole('button', { name: '正在支付…' });
    expect(pendingButton).toBeDisabled();
    expect(pendingButton).toHaveAttribute('aria-busy', 'true');

    finishPayment(paid);

    expect(await screen.findByText('已支付')).toBeInTheDocument();
    expect(mockPayOrder).toHaveBeenCalledTimes(1);
  });

  it('订单过期或已关闭时提示冲突，且不改变页面状态', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);
    vi.mocked(mockPayOrder).mockRejectedValue(
      new ApiError(409, 'ORDER_STATE_CONFLICT', 'order has expired'),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '模拟支付' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('订单已过期或已关闭，无法支付');
    expect(screen.getByText('待支付')).toBeInTheDocument();
  });

  it('支付时订单不存在（或属于他人）显示统一文案', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);
    vi.mocked(mockPayOrder).mockRejectedValue(
      new ApiError(404, 'ORDER_NOT_FOUND', 'order not found'),
    );

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '模拟支付' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('订单不存在或无权查看');
  });

  it('网络异常提示未确认，并说明重复支付不会重复入账', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);
    vi.mocked(mockPayOrder).mockRejectedValue(new ApiError(0, 'NETWORK_ERROR', 'request failed'));

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '模拟支付' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      '网络异常，支付结果未确认，请重试（重复支付不会重复入账）',
    );
  });

  it('已支付的订单展示支付时间，且没有支付按钮', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(paid);

    renderPage();

    expect(await screen.findByText('已支付')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:05:00')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '模拟支付' })).not.toBeInTheDocument();
  });

  it('刷新后按服务端响应变为已关闭，展示关闭时间并隐藏支付按钮', async () => {
    vi.mocked(fetchOrder).mockResolvedValueOnce(sample).mockResolvedValueOnce(closed);

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '刷新订单状态' }));

    expect(await screen.findByText('已关闭')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:15:01')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '模拟支付' })).not.toBeInTheDocument();
    expect(fetchOrder).toHaveBeenCalledTimes(2);
  });

  it('刷新进行中禁用按钮，连续点击只刷新一次', async () => {
    let finishRefresh!: (value: OrderDetail) => void;
    vi.mocked(fetchOrder)
      .mockResolvedValueOnce(sample)
      .mockReturnValueOnce(
        new Promise<OrderDetail>((resolve) => {
          finishRefresh = resolve;
        }),
      );

    renderPage();

    const button = await screen.findByRole('button', { name: '刷新订单状态' });
    await userEvent.click(button);
    await userEvent.click(button);

    expect(fetchOrder).toHaveBeenCalledTimes(2);
    const pending = screen.getByRole('button', { name: '正在刷新…' });
    expect(pending).toBeDisabled();
    expect(pending).toHaveAttribute('aria-busy', 'true');

    finishRefresh(closed);

    expect(await screen.findByText('已关闭')).toBeInTheDocument();
    expect(fetchOrder).toHaveBeenCalledTimes(2);
  });

  it('刷新失败时保留原订单，不把本地状态改成已关闭', async () => {
    vi.mocked(fetchOrder)
      .mockResolvedValueOnce(sample)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'));

    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '刷新订单状态' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('刷新失败，请稍后重试');
    expect(screen.getByText('待支付')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '模拟支付' })).toBeInTheDocument();
    expect(screen.queryByText('已关闭')).not.toBeInTheDocument();
  });

  it('已关闭的订单没有支付按钮', async () => {
    vi.mocked(fetchOrder).mockResolvedValue({
      ...sample,
      status: 'CLOSED',
      closedAt: '2026-10-22T02:15:01Z',
    });

    renderPage();

    expect(await screen.findByText('已关闭')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '模拟支付' })).not.toBeInTheDocument();
  });

  it('订单不存在或属于他人时显示统一的 404 文案', async () => {
    vi.mocked(fetchOrder).mockRejectedValue(
      new ApiError(404, 'ORDER_NOT_FOUND', 'order not found'),
    );

    renderPage('ZZZZZZZZZZ0000000000000001');

    expect(await screen.findByText('订单不存在或无权查看')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '模拟支付' })).not.toBeInTheDocument();
  });

  it('加载失败时显示通用错误并可重试', async () => {
    vi.mocked(fetchOrder)
      .mockRejectedValueOnce(new ApiError(500, 'INTERNAL_ERROR', 'unexpected server error'))
      .mockResolvedValueOnce(sample);

    renderPage();

    expect(await screen.findByRole('alert')).toHaveTextContent('订单加载失败，请稍后重试');

    await userEvent.click(screen.getByRole('button', { name: '重试' }));

    expect(await screen.findByText('待支付')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
