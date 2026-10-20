package com.tokenx.infrastructure.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tokenx.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class HealthControllerTest extends AbstractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void shouldReturnUpStatusOnHealthEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/v1/health"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.service").value("tokenx"))
        .andExpect(jsonPath("$.time").isNotEmpty());
  }

  /**
   * Unknown paths under the API are no longer anonymous: without a token the request is rejected
   * before routing. With a valid token the same path is a 404 (see AuthJwtTest coverage).
   */
  @Test
  void shouldRequireAuthenticationForUnknownPathUnderApiPrefix() throws Exception {
    mockMvc.perform(get("/api/v1/unknown-resource")).andExpect(status().isUnauthorized());
  }
}
