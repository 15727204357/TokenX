package com.tokenx.order.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Properties of the public order number.
 *
 * <p>These assertions are about the shape of the identifier, not about a specific value: an order
 * number is generated, not computed, so pinning an exact string would only test the random source.
 * Nothing here is probabilistic in a way that can flake — 80 random bits do not collide within a few
 * thousand draws.
 */
class OrderNoGeneratorTest {

  private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

  private final OrderNoGenerator generator = new OrderNoGenerator();

  @Test
  void shouldProduceAFixedWidthAsciiBase32Number() {
    for (int index = 0; index < 1_000; index++) {
      String orderNo = generator.next(NOW);

      assertThat(orderNo).hasSize(OrderNoGenerator.LENGTH);
      assertThat(orderNo).matches("[0-9A-HJKMNP-TV-Z]{26}");
    }
  }

  @Test
  void shouldKeepTheTimestampInThePrefixSoInsertsStayRoughlyOrdered() {
    String earlier = generator.next(NOW);
    String later = generator.next(NOW.plusMillis(1));

    assertThat(earlier.substring(0, 10))
        .as("Crockford Base32 preserves ASCII order, so a later instant sorts after an earlier one")
        .isLessThan(later.substring(0, 10));

    assertThat(generator.next(NOW).substring(0, 10))
        .as("the prefix depends only on the instant")
        .isEqualTo(earlier.substring(0, 10));
  }

  @Test
  void shouldNeverReuseANumberAndNeverRunAsACounter() {
    List<String> tails = new ArrayList<>();
    Set<String> numbers = new HashSet<>();
    for (int index = 0; index < 1_000; index++) {
      String orderNo = generator.next(NOW);
      numbers.add(orderNo);
      tails.add(orderNo.substring(10));
    }

    assertThat(numbers).hasSize(1_000);

    List<String> sorted = new ArrayList<>(tails);
    sorted.sort(String::compareTo);
    assertThat(tails)
        .as("a sequence would produce ascending tails; a cryptographic tail will not")
        .isNotEqualTo(sorted);
  }

  @Test
  void shouldNotCollideAcrossThreads() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<List<String>>> tasks = new ArrayList<>();
      for (int task = 0; task < 4; task++) {
        tasks.add(
            () -> {
              List<String> generated = new ArrayList<>();
              for (int index = 0; index < 500; index++) {
                generated.add(generator.next(NOW));
              }
              return generated;
            });
      }

      Set<String> all = new HashSet<>();
      List<Future<List<String>>> futures = new ArrayList<>();
      for (Callable<List<String>> task : tasks) {
        futures.add(pool.submit(task));
      }
      for (Future<List<String>> future : futures) {
        all.addAll(future.get(20, TimeUnit.SECONDS));
      }

      assertThat(all)
          .as("generation takes no lock and still never repeats itself")
          .hasSize(2_000);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void shouldAcceptOnlyAWellFormedOrderNumberFromAClient() {
    assertThat(OrderNoGenerator.isCanonical("0123456789ABCDEFGHJKMNPQRS")).isTrue();
    assertThat(OrderNoGenerator.isCanonical("0123456789abcdefghjkmnpqrs")).isTrue();

    assertThat(OrderNoGenerator.isCanonical(null)).isFalse();
    assertThat(OrderNoGenerator.isCanonical("0123456789ABCDEFGHJKMNPQR")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("0123456789ABCDEFGHJKMNPQRST")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("0123456789ABCDEFGHJKMNPQRU")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("IIIIIIIIIIIIIIIIIIIIIIIIII")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("LLLLLLLLLLLLLLLLLLLLLLLLLL")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("OOOOOOOOOOOOOOOOOOOOOOOOOO")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("UUUUUUUUUUUUUUUUUUUUUUUUUU")).isFalse();
    assertThat(OrderNoGenerator.isCanonical("0123456789ABCDEFGHJKMNPQR-")).isFalse();
  }

  @Test
  void shouldNormaliseToTheUppercaseStoredForm() {
    assertThat(OrderNoGenerator.canonical("0123456789abcdefghjkmnpqrs"))
        .isEqualTo("0123456789ABCDEFGHJKMNPQRS");
  }
}
