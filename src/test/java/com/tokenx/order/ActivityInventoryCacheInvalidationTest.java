package com.tokenx.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.order.model.OrderStatus;
import com.tokenx.support.TestClockConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The cached stock is a display snapshot and must be invalidated after committed stock writes. */
@SpringBootTest
@AutoConfigureMockMvc
class ActivityInventoryCacheInvalidationTest extends AbstractOrderApiTest {

  @Test
  void shouldInvalidateAfterReservationAndTimeoutReleaseCommit() throws Exception {
    String admin = adminToken();
    long activityId = openActivity(admin, 79L, 2L);
    assertThat(publicStock(activityId)).isEqualTo(2);

    Buyer buyer = registerBuyer();
    placeOrder(buyer.token(), activityId, newKey()).andExpect(status().isCreated());
    assertThat(publicStock(activityId)).isEqualTo(1);

    TestClockConfig.advance(PAYMENT_TIMEOUT);
    assertThat(timeoutService.closeNextExpiredOrder()).isTrue();
    assertThat(storedOrders(activityId).getFirst().getStatus()).isEqualTo(OrderStatus.CLOSED);
    assertThat(publicStock(activityId)).isEqualTo(2);
  }

  private int publicStock(long id) throws Exception {
    MvcResult result =
        mockMvc.perform(get("/api/v1/activities/" + id)).andExpect(status().isOk()).andReturn();
    return objectMapper.readTree(body(result)).get("availableStock").asInt();
  }
}
