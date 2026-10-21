import { beforeEach, describe, expect, it, vi } from 'vitest';
import { http } from './http';
import { createSeckillOrder, fetchOrder, mockPayOrder, type OrderDetail } from './orders';

vi.mock('./http', () => ({
  http: { get: vi.fn(), post: vi.fn() },
}));

const order: OrderDetail = {
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

const KEY = '3f2504e0-4f89-11d3-9a0c-0305e82c3301';

describe('订单接口', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('下单时把幂等键放进 Idempotency-Key 头，并且不发送请求体', async () => {
    vi.mocked(http.post).mockResolvedValue({ data: order });

    const created = await createSeckillOrder(7, KEY);

    expect(created).toEqual(order);
    expect(http.post).toHaveBeenCalledTimes(1);

    const [url, body, config] = vi.mocked(http.post).mock.calls[0];
    expect(url).toBe('/activities/7/orders');
    expect(body).toBeUndefined();
    expect(config).toEqual({ headers: { 'Idempotency-Key': KEY } });
  });

  it('按对外单号查询自己的订单', async () => {
    vi.mocked(http.get).mockResolvedValue({ data: order });

    const fetched = await fetchOrder(order.orderNo);

    expect(fetched).toEqual(order);
    expect(http.get).toHaveBeenCalledWith(`/orders/${order.orderNo}`);
  });

  it('模拟支付只按单号调用，不发送任何金额或用户字段', async () => {
    const paid: OrderDetail = { ...order, status: 'PAID', paidAt: '2026-10-22T02:05:00Z' };
    vi.mocked(http.post).mockResolvedValue({ data: paid });

    const result = await mockPayOrder(order.orderNo);

    expect(result).toEqual(paid);
    expect(http.post).toHaveBeenCalledTimes(1);

    const [url, body] = vi.mocked(http.post).mock.calls[0];
    expect(url).toBe(`/orders/${order.orderNo}/mock-pay`);
    expect(body).toBeUndefined();
  });
});
