package com.tokenx.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tokenx.infrastructure.money.AmountLimits;
import com.tokenx.catalog.model.PackageStatus;
import com.tokenx.catalog.model.TokenPackage;
import com.tokenx.support.TestAccounts;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Admin package management: creation defaults, validation, permissions and status transitions. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminProductApiTest extends AbstractCatalogApiTest {

  @Test
  void shouldCreateOffShelfPackageAndReturnOnlyPublicFields() throws Exception {
    String token = adminToken();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/products")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            createBody("入门包", 1000L, 990L))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").isNumber())
            .andExpect(jsonPath("$.name").value("入门包"))
            .andExpect(jsonPath("$.creditAmount").value(1000))
            .andExpect(jsonPath("$.listPriceCent").value(990))
            .andExpect(jsonPath("$.status").value("OFF_SHELF"))
            .andExpect(jsonPath("$.*", hasSize(5)))
            .andReturn();

    long id = trackPackage(result);
    TokenPackage stored = packageMapper.selectById(id);

    assertThat(stored.getStatus()).as("a new package must not be on sale yet")
        .isEqualTo(PackageStatus.OFF_SHELF);
    assertThat(stored.getCreditAmount()).isEqualTo(1000L);
    assertThat(stored.getListPriceCent()).isEqualTo(990L);
  }

  @Test
  void shouldIgnoreStatusSuppliedByTheClientOnCreate() throws Exception {
    String token = adminToken();
    Map<String, Object> body = createBody("想直接上架", 500L, 500L);
    body.put("status", "ON_SALE");
    body.put("stock", 9999);

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/products")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("OFF_SHELF"))
            .andReturn();

    assertThat(packageMapper.selectById(trackPackage(result)).getStatus())
        .as("the server, not the client, decides the initial status")
        .isEqualTo(PackageStatus.OFF_SHELF);
  }

  @Test
  void shouldTrimSurroundingWhitespaceFromTheName() throws Exception {
    String token = adminToken();

    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(createBody("  标准包  ", 100L, 100L))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("标准包"));
  }

  @Test
  void shouldAcceptNameOfExactlyNinetySixCharacters() throws Exception {
    String token = adminToken();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/products")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            createBody("名".repeat(96), 100L, 100L))))
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(packageMapper.selectById(trackPackage(result)).getName()).hasSize(96);
  }

  @Test
  void shouldRejectInvalidCreationPayload() throws Exception {
    String token = adminToken();
    String tooLong = "名".repeat(97);

    for (Map<String, Object> body :
        java.util.List.of(
            createBody("", 100L, 100L),
            createBody("   ", 100L, 100L),
            createBody(tooLong, 100L, 100L),
            createBody("零额度", 0L, 100L),
            createBody("负额度", -1L, 100L),
            createBody("缺额度", null, 100L),
            createBody("零价格", 100L, 0L),
            createBody("负价格", 100L, -1L),
            createBody("缺价格", 100L, null))) {
      mockMvc
          .perform(
              post("/api/v1/admin/products")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  @Test
  void shouldRejectUnparseableCreationPayload() throws Exception {
    String token = adminToken();

    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"坏额度\",\"creditAmount\":\"lots\",\"listPriceCent\":100}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void shouldAcceptAmountsExactlyAtTheUpperBound() throws Exception {
    String token = adminToken();
    String name = "上限包" + TestAccounts.shortSuffix();

    MvcResult result =
        mockMvc
            .perform(
                post("/api/v1/admin/products")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            createBody(
                                name,
                                AmountLimits.MAX_CREDIT_AMOUNT,
                                AmountLimits.MAX_PRICE_CENT))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.creditAmount").value(AmountLimits.MAX_CREDIT_AMOUNT))
            .andReturn();

    TokenPackage stored = packageMapper.selectById(trackPackage(result));
    assertThat(stored.getListPriceCent()).isEqualTo(AmountLimits.MAX_PRICE_CENT);
    assertThat(stored.getCreditAmount()).isEqualTo(AmountLimits.MAX_CREDIT_AMOUNT);
  }

  @Test
  void shouldRejectAmountsAboveTheUpperBound() throws Exception {
    String token = adminToken();
    String creditName = "超额包" + TestAccounts.shortSuffix();
    String priceName = "超价包" + TestAccounts.shortSuffix();

    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        createBody(creditName, AmountLimits.MAX_CREDIT_AMOUNT + 1, 100L))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(containsString("creditAmount")));

    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        createBody(priceName, 100L, AmountLimits.MAX_PRICE_CENT + 1))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.message").value(containsString("listPriceCent")));

    assertThat(packageMapper.selectCount(
            Wrappers.<TokenPackage>lambdaQuery().in(TokenPackage::getName, creditName, priceName)))
        .as("a rejected request must not create a row")
        .isZero();
  }

  @Test
  void shouldValidateThePackageIdOnStatusChanges() throws Exception {
    String token = adminToken();

    for (String id : java.util.List.of("0", "-1", "not-a-number")) {
      MvcResult result =
          mockMvc
              .perform(
                  patch("/api/v1/admin/products/" + id + "/status")
                      .header("Authorization", "Bearer " + token)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content("{\"status\":\"ON_SALE\"}"))
              .andExpect(status().isBadRequest())
              .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
              .andExpect(jsonPath("$.requestId").isNotEmpty())
              .andReturn();

      String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
      assertThat(body)
          .as("errors must not leak stack traces or SQL")
          .doesNotContain("Exception")
          .doesNotContain("SQL")
          .doesNotContain("com.tokenx");
    }

    mockMvc
        .perform(
            patch("/api/v1/admin/products/9999999/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ON_SALE\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
        .andExpect(jsonPath("$.*", hasSize(3)));
  }

  @Test
  void shouldRequireAuthenticationForAdminEndpoints() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBody("匿名创建", 100L, 100L))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

    mockMvc
        .perform(
            patch("/api/v1/admin/products/1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ON_SALE\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
  }

  @Test
  void shouldRefuseOrdinaryUserAtAdminEndpoints() throws Exception {
    String token = userToken();

    mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBody("越权创建", 100L, 100L))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    MvcResult created = mockMvc
        .perform(
            post("/api/v1/admin/products")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createBody("普通用户尝试上架", 100L, 100L))))
        .andExpect(status().isCreated())
        .andReturn();
    long packageId = trackPackage(created);

    mockMvc
        .perform(
            patch("/api/v1/admin/products/" + packageId + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ON_SALE\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(packageMapper.selectById(packageId).getStatus())
        .as("a refused request must not change the row")
        .isEqualTo(PackageStatus.OFF_SHELF);
  }

  @Test
  void shouldPublishAndWithdrawWithoutDeletingTheRow() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "上下架包", 200L, 199L);

    patchStatus(token, id, "ON_SALE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.status").value("ON_SALE"));
    assertThat(packageMapper.selectById(id).getStatus()).isEqualTo(PackageStatus.ON_SALE);

    patchStatus(token, id, "OFF_SHELF")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OFF_SHELF"));

    assertThat(packageMapper.selectById(id))
        .as("withdrawing must keep the row, not delete it")
        .isNotNull();
    assertThat(packageMapper.selectById(id).getStatus()).isEqualTo(PackageStatus.OFF_SHELF);
  }

  @Test
  void shouldTreatRepeatingTheCurrentStatusAsSuccess() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "幂等包", 300L, 299L);

    patchStatus(token, id, "ON_SALE").andExpect(status().isOk());
    patchStatus(token, id, "ON_SALE")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ON_SALE"));

    patchStatus(token, id, "OFF_SHELF").andExpect(status().isOk());
    patchStatus(token, id, "OFF_SHELF")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OFF_SHELF"));

    assertThat(packageMapper.selectById(id).getStatus()).isEqualTo(PackageStatus.OFF_SHELF);
  }

  @Test
  void shouldReturn404WhenChangingStatusOfUnknownPackage() throws Exception {
    String token = adminToken();

    patchStatus(token, 9_999_999L, "ON_SALE")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void shouldRejectUnsupportedStatusValue() throws Exception {
    String token = adminToken();
    long id = createPackage(token, "非法状态包", 400L, 399L);

    for (String status : java.util.List.of("on_sale", "SOLD_OUT", "", "ON_SALE ")) {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("status", status);
      mockMvc
          .perform(
              patch("/api/v1/admin/products/" + id + "/status")
                  .header("Authorization", "Bearer " + token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(body)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    assertThat(packageMapper.selectById(id).getStatus()).isEqualTo(PackageStatus.OFF_SHELF);
  }
}
