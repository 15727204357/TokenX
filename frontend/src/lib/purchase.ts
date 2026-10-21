import { ApiError } from '../api/http';

/**
 * Whether a failure might have left an order behind on the server.
 *
 * <p>These are exactly the cases where the request may have been committed without the browser seeing
 * the response — a network error before the answer arrived, a timeout, or a server-side 5xx after the
 * transaction committed. The next attempt must reuse the same idempotency key, otherwise a retry can
 * buy a second unit of something that was already bought.
 *
 * <p>A business rejection (`400`, `404`, `409`) is the opposite: a definite answer that nothing was
 * created, so a retry is a genuinely new attempt and gets a new key.
 *
 * <p>Shared by both purchase channels because the rule is about HTTP and idempotency, not about which
 * endpoint was called; two copies of it would be two chances to get the safe direction wrong.
 */
export function mightHaveReachedTheServer(error: unknown): boolean {
  return error instanceof ApiError && (error.status === 0 || error.status >= 500);
}
