package com.tokenx.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Public catalogue: guests can read it, only on-sale packages exist for them, and paging is a stable
 * cursor walk rather than an offset.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PublicProductApiTest extends AbstractCatalogApiTest {

  @Test
  void shouldServeListAndDetailWithoutAToken() throws Exception {
    String token = adminToken();
    long id = createPublishedPackage(token, "游客可见包", 500L, 499L);

    mockMvc
        .perform(get("/api/v1/products").param("limit", "100"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.hasMore").isBoolean());

    mockMvc
        .perform(get("/api/v1/products/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.name").value("游客可见包"))
        .andExpect(jsonPath("$.creditAmount").value(500))
        .andExpect(jsonPath("$.listPriceCent").value(499))
        .andExpect(jsonPath("$.status").value("ON_SALE"));
  }

  @Test
  void shouldReturnOnlyOnSalePackagesOrderedByDescendingId() throws Exception {
    String token = adminToken();
    long first = createPublishedPackage(token, "在售一", 100L, 99L);
    createPackage(token, "下架一", 100L, 99L);
    long second = createPublishedPackage(token, "在售二", 200L, 199L);
    createPackage(token, "下架二", 200L, 199L);
    long third = createPublishedPackage(token, "在售三", 300L, 299L);

    List<Long> allIds = allListedIds();
    Set<Long> mine = Set.of(first, second, third);

    assertThat(allIds)
        .as("only on-sale packages may appear")
        .containsAll(mine);

    List<Long> mineInOrder = allIds.stream().filter(mine::contains).toList();
    assertThat(mineInOrder)
        .as("newest id first")
        .containsExactly(third, second, first);

    assertThat(new HashSet<>(allIds))
        .as("no duplicated ids in one walk")
        .hasSize(allIds.size());
  }

  @Test
  void shouldWalkPagesWithTheReturnedCursorWithoutDuplicatesOrGaps() throws Exception {
    String token = adminToken();
    List<Long> published = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      published.add(createPublishedPackage(token, "分页包" + i, 100L + i, 99L + i));
    }
    List<Long> expected = published.stream().sorted((a, b) -> Long.compare(b, a)).toList();

    List<Long> collected = new ArrayList<>();
    Set<Long> seen = new HashSet<>();
    Long cursor = null;
    boolean hasMore = true;
    int pages = 0;

    while (hasMore) {
      MockHttpServletRequestBuilder request = get("/api/v1/products").param("limit", "2");
      if (cursor != null) {
        request.param("cursor", String.valueOf(cursor));
      }

      JsonNode page =
          getJson(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
      JsonNode items = page.get("items");
      pages++;

      assertThat(items.size()).as("a page never exceeds the requested size").isLessThanOrEqualTo(2);

      List<Long> pageIds = new ArrayList<>();
      for (JsonNode item : items) {
        pageIds.add(item.get("id").asLong());
      }
      for (Long id : pageIds) {
        assertThat(seen.add(id)).as("id %s appeared twice across pages", id).isTrue();
      }
      collected.addAll(pageIds);

      hasMore = page.get("hasMore").asBoolean();
      JsonNode nextCursor = page.get("nextCursor");
      if (hasMore) {
        assertThat(pageIds).as("a non-final page must not be empty").isNotEmpty();
        assertThat(nextCursor.isNull())
            .as("hasMore requires a cursor to fetch the next page")
            .isFalse();
        assertThat(nextCursor.asLong())
            .as("the cursor must be the last id of the page")
            .isEqualTo(pageIds.get(pageIds.size() - 1));
        cursor = nextCursor.asLong();
      } else {
        assertThat(nextCursor.isNull())
            .as("the last page must not advertise a cursor")
            .isTrue();
      }

      assertThat(pages).as("pagination must terminate").isLessThan(50);
    }

    assertThat(collected)
        .as("the walk covers every on-sale package in descending id order")
        .containsSubsequence(expected.toArray(Long[]::new));
    assertThat(pages).as("5 rows with limit 2 need at least 3 pages").isGreaterThanOrEqualTo(3);
  }

  @Test
  void shouldRejectOutOfRangePageParameters() throws Exception {
    mockMvc.perform(get("/api/v1/products").param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(get("/api/v1/products").param("limit", "101"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(get("/api/v1/products").param("limit", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(get("/api/v1/products").param("cursor", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

    mockMvc.perform(get("/api/v1/products").param("limit", "100"))
        .andExpect(status().isOk());
  }

  @Test
  void shouldReturnTheSameNotFoundForWithdrawnAndUnknownPackages() throws Exception {
    String token = adminToken();
    long withdrawn = createPublishedPackage(token, "已下架包", 700L, 699L);
    patchStatus(token, withdrawn, "OFF_SHELF").andExpect(status().isOk());

    MvcResult withdrawnResult =
        mockMvc
            .perform(get("/api/v1/products/" + withdrawn))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
            .andReturn();

    MvcResult unknownResult =
        mockMvc
            .perform(get("/api/v1/products/9999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"))
            .andReturn();

    assertThat(stripRequestId(body(withdrawnResult)))
        .as("an unpublished package must be indistinguishable from a missing one")
        .isEqualTo(stripRequestId(body(unknownResult)));
  }

  @Test
  void shouldHideAWithdrawnPackageFromListAndDetailImmediately() throws Exception {
    String token = adminToken();
    long id = createPublishedPackage(token, "即将下架包", 800L, 799L);

    assertThat(allListedIds()).contains(id);

    patchStatus(token, id, "OFF_SHELF").andExpect(status().isOk());

    assertThat(allListedIds()).as("withdrawn packages leave the public list").doesNotContain(id);
    mockMvc
        .perform(get("/api/v1/products/" + id))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void shouldExposeOnlyPublicFields() throws Exception {
    String token = adminToken();
    long id = createPublishedPackage(token, "字段检查包", 900L, 899L);

    mockMvc
        .perform(get("/api/v1/products/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.*", hasSize(5)))
        .andExpect(jsonPath("$.createdAt").doesNotExist())
        .andExpect(jsonPath("$.updatedAt").doesNotExist());

    String listBody =
        body(mockMvc.perform(get("/api/v1/products").param("limit", "100")).andReturn());
    assertThat(listBody).doesNotContain("createdAt").doesNotContain("updatedAt");
  }

  @Test
  void shouldRejectNonNumericPackageId() throws Exception {
    mockMvc
        .perform(get("/api/v1/products/not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /** Walks the whole public catalogue and returns every id in the order the API produced it. */
  private List<Long> allListedIds() throws Exception {
    Set<Long> ids = new LinkedHashSet<>();
    Long cursor = null;
    boolean hasMore = true;
    int pages = 0;

    while (hasMore && pages++ < 50) {
      MockHttpServletRequestBuilder request = get("/api/v1/products").param("limit", "100");
      if (cursor != null) {
        request.param("cursor", String.valueOf(cursor));
      }

      JsonNode page = getJson(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
      for (JsonNode item : page.get("items")) {
        ids.add(item.get("id").asLong());
      }
      hasMore = page.get("hasMore").asBoolean();
      cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asLong();
    }

    return new ArrayList<>(ids);
  }

  private JsonNode getJson(MvcResult result) throws Exception {
    return objectMapper.readTree(body(result));
  }

  private static String stripRequestId(String json) {
    return json.replaceAll("\"requestId\":\"[^\"]*\"", "\"requestId\":\"<id>\"");
  }
}
