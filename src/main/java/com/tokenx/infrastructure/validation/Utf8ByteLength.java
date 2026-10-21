package com.tokenx.infrastructure.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits a string by its UTF-8 <em>byte</em> length rather than by Java character count.
 *
 * <p>This matters for inputs consumed by BCrypt, whose limit is 72 bytes: a 25-character Chinese
 * password is 75 bytes and would make the encoder throw, while a plain {@code @Size(max = 72)}
 * accepts it. Validation belongs at the edge so the caller gets a stable 400 instead of a 500.
 */
@Documented
@Constraint(validatedBy = Utf8ByteLengthValidator.class)
@Target({
  ElementType.FIELD,
  ElementType.METHOD,
  ElementType.PARAMETER,
  ElementType.RECORD_COMPONENT,
  ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface Utf8ByteLength {

  /** Maximum number of bytes once the value is encoded as UTF-8. */
  int max();

  String message() default "must be at most {max} bytes when UTF-8 encoded";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
