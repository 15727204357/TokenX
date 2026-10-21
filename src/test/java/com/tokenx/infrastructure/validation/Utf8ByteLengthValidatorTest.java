package com.tokenx.infrastructure.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

/**
 * Boundary behaviour of the UTF-8 byte limit.
 *
 * <p>The interesting cases are the ones a character-count check gets wrong: a short string in
 * characters can still exceed the byte budget.
 */
class Utf8ByteLengthValidatorTest {

  private static final int MAX_BYTES = 72;

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  record Sample(@Utf8ByteLength(max = MAX_BYTES) String value) {}

  @Test
  void shouldAcceptValuesExactlyAtTheByteLimit() {
    assertThat(isValid("a".repeat(72))).as("72 ASCII characters = 72 bytes").isTrue();
    assertThat(isValid("密".repeat(24))).as("24 CJK characters = 72 bytes").isTrue();
  }

  @Test
  void shouldRejectValuesOverTheByteLimitEvenWhenShortInCharacters() {
    assertThat(isValid("密".repeat(25))).as("25 characters but 75 bytes").isFalse();
    assertThat(isValid("a".repeat(70) + "密")).as("71 characters but 73 bytes").isFalse();
    assertThat(isValid("a".repeat(73))).isFalse();
  }

  @Test
  void shouldTreatNullAsValidSoPresenceStaysSeparate() {
    assertThat(isValid(null)).isTrue();
  }

  private boolean isValid(String value) {
    return validator.validate(new Sample(value)).isEmpty();
  }
}
