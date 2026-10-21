package com.tokenx.order.support;

import com.tokenx.infrastructure.error.BusinessException;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * The {@code Idempotency-Key} header, and how it is stored.
 *
 * <p>The wire format is a plain canonical UUID: 36 characters, hyphenated, the form every client
 * library already produces. It is accepted only in exactly that shape — {@link UUID#fromString}
 * alone is too permissive (it happily parses {@code 1-1-1-1-1}), so a malformed key would otherwise
 * reach the unique key and be answered as a conflict instead of a bad request.
 *
 * <p>Storage is {@code BINARY(16)}: 16 bytes instead of 36, and {@code uk_user_request} is a 24-byte
 * logical key instead of a 44-byte one. The conversion is fixed-width in both directions, so a round
 * trip is lossless and there is no textual form to normalise later.
 */
public final class IdempotencyKeys {

  /** The header name, in the spelling the API documents. */
  public static final String HEADER = "Idempotency-Key";

  /** Eight-four-four-four-twelve hexadecimal digits, lowercase or uppercase. */
  private static final Pattern CANONICAL =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  private static final String VALIDATION_FAILED = "VALIDATION_FAILED";

  private IdempotencyKeys() {}

  /**
   * Parses the header value into the request key.
   *
   * <p>A missing header is not treated as "generate one for me": without a client-chosen key a
   * retried request would silently create a second order attempt, and the caller could not tell a
   * retry from a new purchase.
   *
   * @throws BusinessException {@code 400 VALIDATION_FAILED} when absent or not a canonical UUID
   */
  public static UUID parse(String header) {
    if (header == null || header.isEmpty()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          VALIDATION_FAILED,
          "Idempotency-Key header is required and must be a canonical UUID");
    }
    if (!CANONICAL.matcher(header).matches()) {
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          VALIDATION_FAILED,
          "Idempotency-Key must be a canonical UUID, for example "
              + "3f2504e0-4f89-11d3-9a0c-0305e82c3301");
    }
    return UUID.fromString(header);
  }

  /** 16 bytes, most-significant half first — the layout MySQL's {@code BINARY(16)} stores as-is. */
  public static byte[] toBytes(UUID value) {
    return ByteBuffer.allocate(16)
        .putLong(value.getMostSignificantBits())
        .putLong(value.getLeastSignificantBits())
        .array();
  }

  /** The inverse of {@link #toBytes(UUID)}; used by tests that read the column back. */
  public static UUID fromBytes(byte[] bytes) {
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }
}
