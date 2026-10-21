import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '../api/http';
import { fetchOrder, type OrderDetail } from '../api/orders';
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

function renderPage(orderNo = sample.orderNo) {
  return render(
    <MemoryRouter initialEntries={[`/orders/${orderNo}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/orders/:orderNo" element={<OrderPage />} />
          <Route path="/login" element={<p>登录页标记</p>} />
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

  it('不提供支付按钮，也不显示倒计时', async () => {
    vi.mocked(fetchOrder).mockResolvedValue(sample);

    renderPage();

    await screen.findByText('待支付');

    expect(screen.queryByRole('button', { name: /支付/ })).not.toBeInTheDocument();
    expect(screen.queryByText(/剩余/)).not.toBeInTheDocument();
    expect(
      screen.getByText('支付功能将在后续版本开放，本页仅展示服务端记录的订单信息。'),
    ).toBeInTheDocument();
  });

  it('已支付的订单展示支付时间', async () => {
    vi.mocked(fetchOrder).mockResolvedValue({
      ...sample,
      status: 'PAID',
      paidAt: '2026-10-22T02:05:00Z',
    });

    renderPage();

    expect(await screen.findByText('已支付')).toBeInTheDocument();
    expect(screen.getByText('2026-10-22 10:05:00')).toBeInTheDocument();
  });

  it('订单不存在或属于他人时显示统一的 404 文案', async () => {
    vi.mocked(fetchOrder).mockRejectedValue(
      new ApiError(404, 'ORDER_NOT_FOUND', 'order not found'),
    );

    renderPage('ZZZZZZZZZZ0000000000000001');

    expect(await screen.findByText('订单不存在或无权查看')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '重试' })).not.toBeInTheDocument();
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
