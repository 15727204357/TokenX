import { http } from './http';

/** Sale channel of an order. Only `SECKILL` exists in this stage. */
export type OrderType = 'SECKILL' | 'NORMAL';

/** Server-side order state. The page renders this verbatim; it never derives it from a timer. */
export type OrderStatus = 'UNPAID' | 'PAID' | 'CLOSED';

/**
 * One order, exactly as the server sends it.
 *
 * <p>Every amount, timestamp and name below is a snapshot the server took when the order was
 * created. The page must display them and may not recompute any of them: the price is not
 * `activityPriceCent` and the deadline is not "created plus fifteen minutes" as far as the browser
 * is concerned.
 */
export interface OrderDetail {
  orderNo: string;
  orderType: OrderType;
  activityId: number | null;
  packageId: number;
  packageName: string;
  creditAmount: number;
  payAmountCent: number;
  status: OrderStatus;
  expiresAt: string;
  paidAt: string | null;
  closedAt: string | null;
  createdAt: string;
}

/**
 * Creates one seckill order.
 *
 * <p>There is no request body: the activity decides the price, the credits and the deadline, and the
 * access token decides who is buying.
 *
 * <p>The idempotency key is supplied by the caller, not generated here, because it has to survive a
 * retry: the same key means "the same purchase attempt", so a second call after a network failure
 * returns the order the first call may already have created instead of buying a second unit.
 *
 * @param activityId activity being bought
 * @param idempotencyKey canonical UUID identifying this purchase attempt
 */
export async function createSeckillOrder(
  activityId: number,
  idempotencyKey: string,
): Promise<OrderDetail> {
  const { data } = await http.post<OrderDetail>(`/activities/${activityId}/orders`, undefined, {
    headers: { 'Idempotency-Key': idempotencyKey },
  });
  return data;
}

/** One of the caller's own orders. Someone else's order answers exactly like a missing one. */
export async function fetchOrder(orderNo: string): Promise<OrderDetail> {
  const { data } = await http.get<OrderDetail>(`/orders/${orderNo}`);
  return data;
}
