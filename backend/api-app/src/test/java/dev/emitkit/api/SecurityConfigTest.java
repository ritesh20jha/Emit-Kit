package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest(properties = "emitkit.cors.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
@Import(RedisTestConfig.class)
class SecurityConfigTest {

  @Autowired private MockMvc mvc;
  @Autowired private StringRedisTemplate redis;

  /** Boot 4 does not wire Spring Security's MockMvc support, so @WithMockUser needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  @Test
  void apiWithoutSession_is401Problem() throws Exception {
    mvc.perform(get("/api/v1/test"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
  }

  @Test
  @WithMockUser
  void postWithoutCsrf_is403() throws Exception {
    mvc.perform(post("/api/v1/test"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser
  void postWithCsrf_isOk() throws Exception {
    mvc.perform(post("/api/v1/test").with(csrf())).andExpect(status().isOk());
  }

  @Test
  void webhookPathNeedsNoCsrfOrSession() throws Exception {
    mvc.perform(post("/webhooks/test"))
        .andExpect(status().isOk())
        .andExpect(cookie().doesNotExist("SESSION"));
  }

  @Test
  @WithMockUser
  void corsAllowedOrigin_getsHeader() throws Exception {
    mvc.perform(get("/api/v1/test").header("Origin", "http://localhost:5173"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  @Test
  void corsForeignOrigin_rejected() throws Exception {
    mvc.perform(get("/api/v1/test").header("Origin", "https://evil.example"))
        .andExpect(status().isForbidden());
  }

  @Test
  void anonymousApiRequest_setsNoSessionCookie() throws Exception {
    mvc.perform(get("/api/v1/test"))
        .andExpect(status().isUnauthorized())
        .andExpect(cookie().doesNotExist("SESSION"));
  }

  @Test
  void anonymousErrorDispatch_isNotChallenged() throws Exception {
    RequestPostProcessor errorDispatch =
        request -> {
          request.setDispatcherType(DispatcherType.ERROR);
          request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
          return request;
        };
    mvc.perform(get("/error").with(errorDispatch)).andExpect(status().isNotFound());
  }

  @Test
  void apiDocsArePublic() throws Exception {
    mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void getIssuesXsrfCookie_notHttpOnly() throws Exception {
    mvc.perform(get("/api/v1/test"))
        .andExpect(status().isOk())
        .andExpect(cookie().exists("XSRF-TOKEN"))
        .andExpect(cookie().httpOnly("XSRF-TOKEN", false));
  }

  @Test
  @WithMockUser
  void postWithEchoedXsrfHeader_isOk() throws Exception {
    String token = xsrfToken();
    mvc.perform(
            post("/api/v1/test")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", token))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser
  void postWithMismatchedXsrfHeader_is403() throws Exception {
    String token = xsrfToken();
    mvc.perform(
            post("/api/v1/test")
                .cookie(new Cookie("XSRF-TOKEN", token))
                .header("X-XSRF-TOKEN", "not-the-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser
  void sessionIsStoredUnderEmitkitNamespace() throws Exception {
    mvc.perform(get("/api/v1/test/session")).andExpect(status().isOk());
    assertThat(redis.keys("emitkit:*")).isNotEmpty();
  }

  private String xsrfToken() throws Exception {
    return mvc.perform(get("/api/v1/test"))
        .andReturn()
        .getResponse()
        .getCookie("XSRF-TOKEN")
        .getValue();
  }
}
