package dev.emitkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * The CON-03 endpoints: runs, logs, files, artifact, lint and versions. Every one answers 501
 * NOT_IMPLEMENTED until its feature ticket replaces the body, and the request DTOs validate their
 * input. Runs with the real security chain, as {@link CON02ContractTest} does.
 */
@WebMvcTest
@AutoConfigureMockMvc
@EnableWebSecurity
@Import({SecurityConfig.class, ProblemDetailsAdvice.class})
class CON03ContractTest {

  /** Boot 4 does not wire Spring Security's MockMvc support, so @WithMockUser needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  @Autowired private MockMvc mvc;

  @Test
  @WithMockUser
  void runsListFiltersBindEnums() throws Exception {
    mvc.perform(get("/api/v1/projects/p1/runs?status=BOGUS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  @WithMockUser
  void approvalRequiresDecision() throws Exception {
    mvc.perform(
            post("/api/v1/runs/r1/approval")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"comment\":\"ship it\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("decision"));
  }

  /** The body column is empty for requests that carry none. */
  @ParameterizedTest
  @WithMockUser
  @CsvSource({
    "GET,/api/v1/projects/p1/runs?cursor=c1&limit=10&status=RUNNING&trigger=MANUAL,",
    "POST,/api/v1/projects/p1/runs,'{\"dryRun\":true}'",
    "GET,/api/v1/runs/r1,",
    "POST,/api/v1/runs/r1/cancel,",
    "POST,/api/v1/runs/r1/rerun,",
    "POST,/api/v1/runs/r1/approval,'{\"decision\":\"APPROVED\",\"comment\":\"ship it\"}'",
    "GET,/api/v1/runs/r1/logs?afterSeq=10,",
    "GET,/api/v1/runs/r1/logs/stream,",
    "GET,/api/v1/runs/r1/files,",
    "GET,/api/v1/runs/r1/files/src/openapi.yaml?diff=true,",
    "GET,/api/v1/runs/r1/artifact,",
    "GET,/api/v1/runs/r1/lint,",
    "GET,/api/v1/projects/p1/versions,"
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
