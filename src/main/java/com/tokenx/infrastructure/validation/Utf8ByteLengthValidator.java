package com.tokenx.infrastructure.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/**
 * Checks {@link Utf8ByteLength}.
 *
 * <p>{@code null} is considered valid: presence is a different constraint ({@code @NotBlank}), and
 * mixing both concerns would make the message depend on which validator ran first.
 */
public class Utf8ByteLengthValidator implements ConstraintValidator<Utf8ByteLength, CharSequence> {

  private int maxBytes;

  @Override
  public void initialize(Utf8ByteLength annotation) {
    this.maxBytes = annotation.max();
  }

  @Override
  public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    return value.toString().getBytes(StandardCharsets.UTF_8).length <= maxBytes;
  }
}
