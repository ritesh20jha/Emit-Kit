package dev.emitkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.config.SecurityConfig;
import dev.emitkit.api.error.ProblemDetailsAdvice;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The CON-02 endpoints: projects, config, spec and lint. Every one answers 501 NOT_IMPLEMENTED
 * until its feature ticket replaces the body, and the request DTOs validate their input. Runs with
 * the real security chain, as {@link CON01ContractTest} does.
 */
@WebMvcTest
@AutoConfigureMockMvc
@EnableWebSecurity
@Import({SecurityConfig.class, ProblemDetailsAdvice.class})
class CON02ContractTest {

  /** Boot 4 does not wire Spring Security's MockMvc support, so @WithMockUser needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String CREATE_PROJECT =
      "{\"mode\":\"EXISTING\",\"repo\":\"acme/widgets\",\"specPath\":\"openapi.yaml\","
          + "\"defaultBranch\":\"main\",\"outputMode\":\"APPEND\"}";

  private static final String CONFIG =
      "{\"outputs\":[\"SDK\"],\"outputDir\":\"generated\",\"versionStrategy\":\"EMITKIT_MANAGED\","
          + "\"breakingGate\":\"BLOCK\",\"lintFailOn\":\"ERROR\"}";

  @Autowired private MockMvc mvc;

  @Test
  @WithMockUser
  void createProject_validatesRepoFormat() throws Exception {
    mvc.perform(
            post("/api/v1/orgs/org1/projects")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_PROJECT.replace("acme/widgets", "not a repo")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("repo"));
  }

  @Test
  @WithMockUser
  void lintIs501UntilImplemented() throws Exception {
    mvc.perform(
            post("/api/v1/lint")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"openapi: 3.1.0\"}"))
        .andExpect(status().is(501))
        .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
  }

  @Test
  @WithMockUser
  void lintRequestRequiresContent() throws Exception {
    mvc.perform(
            post("/api/v1/lint").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("content"));
  }

  @Test
  @WithMockUser
  void configRequestValidatesBody() throws Exception {
    mvc.perform(
            request(HttpMethod.PUT, "/api/v1/projects/p1/config")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  /** The body column is empty for requests that carry none. */
  @ParameterizedTest
  @WithMockUser
  @CsvSource({
    "GET,/api/v1/orgs/org1/projects?cursor=c1&limit=10&q=web&status=FAILED,",
    "POST,/api/v1/orgs/org1/projects,'" + CREATE_PROJECT + "'",
    "GET,/api/v1/orgs/org1/vcs-repos?q=web,",
    "GET,/api/v1/orgs/org1/vcs-repos/acme/widgets/spec-check?path=openapi.yaml&branch=main,",
    "GET,/api/v1/projects/p1,",
    "DELETE,/api/v1/projects/p1,",
    "POST,/api/v1/projects/p1/webhook,",
    "GET,/api/v1/projects/p1/config,",
    "PUT,/api/v1/projects/p1/config,'" + CONFIG + "'",
    "GET,/api/v1/projects/p1/spec,",
    "PUT,/api/v1/projects/p1/spec,'{\"content\":\"openapi: 3.1.0\",\"sha\":\"abc\",\"generate\":true}'",
    "GET,/api/v1/projects/p1/spec/snapshots,",
    "GET,/api/v1/projects/p1/spec/diff?from=s1&to=s2,",
    "POST,/api/v1/lint,'{\"content\":\"openapi: 3.1.0\"}'"
  })
  void contractEndpointIs501(String method, String path, @Nullable String body) throws Exception {
    MockHttpServletRequestBuilder request = request(HttpMethod.valueOf(method), path).with(csrf());
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
    mvc.perform(request)
        .andExpect(status().is(501))
        .andExpect(jsonPath("$.code").value("NOT_IMPLEMENTED"));
  }
}
