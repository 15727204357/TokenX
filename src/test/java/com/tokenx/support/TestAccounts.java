package com.tokenx.support;

import java.util.UUID;

/** Small helpers shared by the auth tests. */
public final class TestAccounts {

  private TestAccounts() {}

  /** A username that satisfies the 3-64 letters/digits/underscore rule and is unique per call. */
  public static String uniqueUsername(String prefix) {
    return prefix + uuid();
  }

  /** Eight hex characters, safe inside the username character set. */
  public static String shortSuffix() {
    return uuid().substring(0, 8);
  }

  private static String uuid() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }

  public static String password() {
    return "Passw0rd-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
