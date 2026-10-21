package com.tokenx.order.support;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Generates the public order number: 26 Crockford Base32 characters.
 *
 * <p>Why this shape:
 *
 * <ul>
 *   <li><b>26 characters × 5 bits = 130 bits.</b> The first 10 characters hold the creation time in
 *       milliseconds (48 bits, the remaining 2 are zero), the last 16 hold an 80-bit random tail
 *       from a cryptographically strong source.
 *   <li><b>Not enumerable.</b> 80 random bits mean knowing one order number says nothing about the
 *       next one, so public identifiers cannot be walked. This is also why there is no database
 *       sequence: it would be guessable and would become a global write hotspot.
 *   <li><b>No business meaning.</b> No user id, no activity id, no shard, no date-parsing contract.
 *       The leading time prefix only keeps inserts roughly append-only in the unique index; nothing
 *       may ever read a business fact out of it.
 *   <li><b>No locking.</b> No {@code synchronized} and no shared counter, so generation never
 *       serialises concurrent buyers. The astronomically unlikely collision is caught by {@code
 *       uk_order_no} and resolved by the caller.
 * </ul>
 *
 * <p>The alphabet is Crockford Base32 ({@code 0-9A-Z} without {@code I}, {@code L}, {@code O},
 * {@code U}), always uppercase, which matches the {@code CHAR(26) ascii_bin} column: fixed width,
 * ASCII only, and the stored value is exactly the value handed to a client.
 */
@Component
public class OrderNoGenerator {

  /** Fixed width, enforced by the {@code CHAR(26)} column. */
  public static final int LENGTH = 26;

  private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

  private static final int TIME_CHARS = 10;

  private static final int RANDOM_BYTES = 10;

  /** Uppercase Crockford Base32, exactly 26 characters. Used to validate a path variable. */
  private static final Pattern FORMAT = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private final SecureRandom random = new SecureRandom();

  /**
   * One order number for an order created at {@code createdAt}.
   *
   * <p>The instant only feeds the prefix; a duplicate still has to be handled by the caller, which
   * regenerates a bounded number of times.
   */
  public String next(Instant createdAt) {
    char[] chars = new char[LENGTH];

    long millis = createdAt.toEpochMilli();
    for (int index = TIME_CHARS - 1; index >= 0; index--) {
      chars[index] = ALPHABET[(int) (millis & 31)];
      millis >>>= 5;
    }

    byte[] tail = new byte[RANDOM_BYTES];
    random.nextBytes(tail);
    int index = TIME_CHARS;
    int accumulator = 0;
    int bits = 0;
    for (byte value : tail) {
      accumulator = (accumulator << 8) | (value & 0xFF);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        chars[index++] = ALPHABET[(accumulator >>> bits) & 31];
      }
    }

    return new String(chars);
  }

  /**
   * Whether a client-supplied order number can exist at all.
   *
   * <p>Case is normalised rather than rejected: the stored form is uppercase, and refusing a
   * lowercase path segment would only create a support question. Everything else — wrong length,
   * an excluded letter, punctuation — is a malformed request, not a missing order.
   */
  public static boolean isCanonical(String candidate) {
    return candidate != null && FORMAT.matcher(candidate.toUpperCase(Locale.ROOT)).matches();
  }

  /** The stored form of a candidate that already passed {@link #isCanonical(String)}. */
  public static String canonical(String candidate) {
    return candidate.toUpperCase(Locale.ROOT);
  }
}
