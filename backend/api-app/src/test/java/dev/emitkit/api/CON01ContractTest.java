package dev.emitkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.auth.GitHubUserService;
import dev.emitkit.api.auth.LoginSuccessHandler;
import dev.emitkit.api.config.SecurityConfig;
import dev.emitkit.api.error.ProblemDetailsAdvice;
import dev.emitkit.api.orgs.OrgSyncService;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.UserRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The contract ticket's endpoints: the /me endpoints answer 501 NOT_IMPLEMENTED until their feature
 * ticket replaces the body, and the member PATCH request validates its input. The org and member
 * endpoints are covered by their own ITs. Runs with the real security chain so that CSRF and
 * authentication apply as they do in the application.
 */
@WebMvcTest(properties = {GitHubTestCredentials.CLIENT_ID, GitHubTestCredentials.CLIENT_SECRET})
@AutoConfigureMockMvc
@EnableWebSecurity
@Import({SecurityConfig.class, ProblemDetailsAdvice.class})
class CON01ContractTest {

  /** Boot 4 does not wire Spring Security's MockMvc support, so @WithMockUser needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  @Autowired private MockMvc mvc;

  // SecurityConfig and the controllers depend on these, and this slice does not load them.
  @MockitoBean private GitHubUserService gitHubUserService;
  @MockitoBean private LoginSuccessHandler loginSuccessHandler;
  @MockitoBean private UserRepository users;
  @MockitoBean private OrgMemberRepository orgMembers;
  @MockitoBean private OrganizationRepository organizations;
  @MockitoBean private OrgAccessService orgAccess;
  @MockitoBean private OrgSyncService orgSync;
  @MockitoBean private StringRedisTemplate redis;

  @Test
  @WithMockUser
  void patchRoleValidatesBody() throws Exception {
    mvc.perform(
            patch("/api/v1/orgs/org1/members/user1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors[0].field").value("role"));
  }

  @Test
  @WithMockUser
  void patchRoleRejectsUnknownRole() throws Exception {
    mvc.perform(
            patch("/api/v1/orgs/org1/members/user1")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"OWNER\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @ParameterizedTest
  @WithMockUser
  @CsvSource({
    "GET,/api/v1/me/vcs-quota,",
    "GET,/api/v1/orgs,",
    "POST,/api/v1/me/sync-orgs,
    "GET,/api/v1/orgs/org1,",
    "GET,/api/v1/orgs/org1/members,",
    "PATCH,/api/v1/orgs/org1/members/user1,{\"role\":\"EDITOR\"}"
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
