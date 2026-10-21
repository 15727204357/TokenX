package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.tokenx.order.service.SeckillOrderService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Reading an order back, and who is allowed to.
 *
 * <p>The whole point of these tests is that a buyer cannot learn anything about other buyers: a
 * well-formed order number that belongs to somebody else answers exactly like one that does not
 * exist, down to the error message.
 *
 * <p>It also pins down what must never reach the log. A credential in a log file is a credential
 * leak, and the request id is a sufficient correlation handle.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderQueryApiTest extends AbstractOrderApiTest {

  @MockitoSpyBean private SeckillOrderService seckillOrderServiceSpy;

  @Test
  void shouldReturnTheCallersOwnOrder() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String orderNo =
        orderNoOf(
            placeOrder(buyer.token(), activityId, newKey())
                .andExpect(status().isCreated())
                .andReturn());

    MvcResult result = getOrder(buyer.token(), orderNo).andExpect(status().isOk()).andReturn();
    Map<String, Object> body = payload(result);

    assertThat(body.get("orderNo")).isEqualTo(orderNo);
    assertThat(body.get("status")).isEqualTo("UNPAID");
    assertThat(body.get("payAmountCent")).isEqualTo(799);
    assertThat(body).doesNotContainKeys("id", "userId", "requestId");
  }

  @Test
  void shouldAcceptALowercaseOrderNumberInThePath() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String orderNo =
        orderNoOf(placeOrder(buyer.token(), activityId, newKey()).andReturn());

    MvcResult result =
        getOrder(buyer.token(), orderNo.toLowerCase()).andExpect(status().isOk()).andReturn();
    assertThat(payload(result).get("orderNo")).isEqualTo(orderNo);
  }

  @Test
  void shouldNotDistinguishAnotherUsersOrderFromAMissingOne() throws Exception {
    String admin = adminToken();
    Buyer owner = registerBuyer();
    Buyer stranger = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String orderNo = orderNoOf(placeOrder(owner.token(), activityId, newKey()).andReturn());

    MvcResult foreign = getOrder(stranger.token(), orderNo).andReturn();
    MvcResult missing = getOrder(stranger.token(), "0".repeat(26)).andReturn();

    assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
    assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    assertThat(errorCodeOf(foreign)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(errorCodeOf(missing)).isEqualTo("ORDER_NOT_FOUND");
    assertThat(json(foreign).get("message").asText())
        .as("the message must be identical, or the difference becomes an oracle")
        .isEqualTo(json(missing).get("message").asText());
    assertThat(body(foreign))
        .as("only the request id may differ between the two responses")
        .isEqualTo(body(missing).replace(json(missing).get("requestId").asText(),
            json(foreign).get("requestId").asText()));
  }

  @Test
  void shouldRejectAMalformedOrderNumber() throws Exception {
    Buyer buyer = registerBuyer();

    for (String candidate : malformedOrderNumbers()) {
      MvcResult result = getOrder(buyer.token(), candidate).andReturn();
      assertThat(result.getResponse().getStatus())
          .as("a value that cannot be an order number is a bad request: %s", candidate)
          .isEqualTo(400);
      assertThat(errorCodeOf(result)).isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void shouldRejectAMalformedActivityId() throws Exception {
    Buyer buyer = registerBuyer();
    String key = newKey();

    for (String candidate : List.of("abc", "0", "-1")) {
      MvcResult result =
          mockMvc
              .perform(
                  post("/api/v1/activities/" + candidate + "/orders")
                      .header("Authorization", "Bearer " + buyer.token())
                      .header("Idempotency-Key", key))
              .andReturn();
      assertThat(result.getResponse().getStatus())
          .as("an activity id that cannot exist is a bad request, not a server error: %s", candidate)
          .isEqualTo(400);
      assertThat(errorCodeOf(result)).isEqualTo("VALIDATION_FAILED");
    }
  }

  @Test
  void shouldRequireATokenForBothEndpoints() throws Exception {
    MvcResult create =
        mockMvc
            .perform(
                post("/api/v1/activities/1/orders")
                    .header("Idempotency-Key", newKey()))
            .andReturn();
    assertThat(create.getResponse().getStatus()).isEqualTo(401);
    assertThat(errorCodeOf(create)).isEqualTo("UNAUTHORIZED");

    MvcResult detail = mockMvc.perform(get("/api/v1/orders/" + "0".repeat(26))).andReturn();
    assertThat(detail.getResponse().getStatus()).isEqualTo(401);
    assertThat(errorCodeOf(detail)).isEqualTo("UNAUTHORIZED");
  }

  @Test
  void shouldNeverWriteTheCredentialIntoTheLog() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);
    String key = newKey();

    List<String> messages =
        captureLogs(
            () -> {
              placeOrder(buyer.token(), activityId, key).andExpect(status().isCreated());
              placeOrder(buyer.token(), activityId, key).andExpect(status().isOk());
            });

    assertThat(messages).noneMatch(message -> message.contains(buyer.token()));
    assertThat(messages).noneMatch(message -> message.contains("Bearer "));
  }

  @Test
  void shouldLogAnUnexpectedFailureByTypeAndPathOnly() throws Exception {
    String admin = adminToken();
    Buyer buyer = registerBuyer();
    long activityId = openActivity(admin, 799L, 5L);

    doThrow(new IllegalStateException("boom"))
        .when(seckillOrderServiceSpy)
        .create(anyLong(), anyLong(), any(UUID.class));

    List<String> messages =
        captureLogs(
            () -> {
              MvcResult result =
                  placeOrder(buyer.token(), activityId, newKey())
                      .andExpect(status().isInternalServerError())
                      .andReturn();
              assertThat(errorCodeOf(result)).isEqualTo("INTERNAL_ERROR");
            });

    assertThat(messages)
        .as("the failure is logged with its type and path, and nothing else")
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains("unexpected failure")
                    .contains("IllegalStateException")
                    .contains("/api/v1/activities/" + activityId + "/orders"));
    assertThat(messages).noneMatch(message -> message.contains(buyer.token()));
  }

  // --- helpers --------------------------------------------------------------------------------

  private List<String> malformedOrderNumbers() {
    List<String> candidates = new ArrayList<>();
    candidates.add("abc");
    candidates.add("0".repeat(25));
    candidates.add("0".repeat(27));
    // Crockford Base32 excludes I, L, O and U precisely so they cannot be confused with 1 and 0.
    candidates.add("I".repeat(26));
    candidates.add("L".repeat(26));
    candidates.add("O".repeat(26));
    candidates.add("U".repeat(26));
    candidates.add("0".repeat(25) + "!");
    candidates.add("0".repeat(8) + "-" + "0".repeat(17));
    return candidates;
  }

  /**
   * Runs an action with a Logback appender attached to the root logger and returns every message.
   *
   * <p>Attaching at the root is deliberate: the assertion must also catch a log line written by a
   * library, not just by this project's classes.
   */
  private List<String> captureLogs(ThrowingAction action) throws Exception {
    Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    root.addAppender(appender);
    try {
      action.run();
    } finally {
      root.detachAppender(appender);
      appender.stop();
    }
    return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
  }

  /** A body that may throw, so a log capture can wrap request code without swallowing exceptions. */
  @FunctionalInterface
  private interface ThrowingAction {
    void run() throws Exception;
  }
}
