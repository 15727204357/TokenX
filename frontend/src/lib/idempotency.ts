/**
 * Idempotency keys for the purchase flow.
 *
 * <p>The backend accepts only a canonical UUID, so the key is produced by the browser's own
 * cryptographically strong generator rather than from `Math.random()` or a timestamp: two tabs, or
 * two buyers, must not be able to produce the same key by accident.
 *
 * <p>Ownership of the key matters more than its shape. A key belongs to one <em>attempt</em>, not to
 * one click: after a failure that might have reached the server (network error, timeout, 5xx) the
 * same key must be sent again, otherwise a retry could buy a second unit.
 */
export function newIdempotencyKey(): string {
  return crypto.randomUUID();
}
