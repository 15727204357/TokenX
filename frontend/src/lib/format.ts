/**
 * Display-only formatting helpers.
 *
 * <p>Nothing here decides business state: prices are already integer cents on the server, and
 * whether an activity is upcoming, active or ended is answered by the server's `runtimeStatus`, not
 * by comparing the rendered text with the browser clock. Formatting a time must never change what
 * the page claims about an activity.
 */

/** Integer cents rendered as "¥12.34". Display only: the stored value stays an integer. */
export function formatPrice(cent: number): string {
  const yuan = Math.floor(cent / 100);
  const remainder = cent % 100;
  return `¥${yuan}.${String(remainder).padStart(2, '0')}`;
}

// A fixed presentation zone, so two clients in different timezones see the same window and tests
// are not at the mercy of the machine's locale.
const DATE_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
});

/** An ISO-8601 instant rendered as "YYYY-MM-DD HH:mm:ss" (Asia/Shanghai, UTC+8). */
export function formatInstant(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const parts = DATE_TIME.formatToParts(date).reduce<Record<string, string>>((acc, part) => {
    acc[part.type] = part.value;
    return acc;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}
