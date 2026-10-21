package com.tokenx.auth.dto;

/**
 * Password rules shared by registration and login.
 *
 * <p>{@link #MAX_BYTES} exists because BCrypt's input limit is 72 <em>bytes</em>: the same value must
 * gate both endpoints, otherwise a login could reach the encoder with an input that registration
 * already rejects.
 */
public final class PasswordPolicy {

  /** Minimum number of characters, independent of encoding. */
  public static final int MIN_CHARS = 8;

  /** Maximum number of UTF-8 bytes accepted by BCrypt. */
  public static final int MAX_BYTES = 72;

  private PasswordPolicy() {}
}
