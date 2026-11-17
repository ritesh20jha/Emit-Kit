package dev.emitkit.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.ProjectAccess;
import dev.emitkit.api.auth.GitHubUserService;
import dev.emitkit.api.auth.LoginSuccessHandler;
import dev.emitkit.api.config.SecurityConfig;
import dev.emitkit.api.error.ProblemDetailsAdvice;
import dev.emitkit.api.orgs.OrgSyncService;
import dev.emitkit.api.projects.ConfigService;
import dev.emitkit.api.projects.ProjectService;
import dev.emitkit.api.projects.SpecService;
import dev.emitkit.api.runs.RunFilesService;
import dev.emitkit.api.runs.RunService;
import dev.emitkit.api.runs.VersionService;
import dev.emitkit.api.spec.LintRateLimiter;
import dev.emitkit.api.spec.SpecHistoryService;
import dev.emitkit.api.webhooks.DeliveryDeduper;
import dev.emitkit.api.webhooks.WebhookDispatcher;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.WebhookVerifier;
import org.junit.jupiter.api.Test;
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
 * The CON-02 endpoints: projects, config, spec and lint. Each one still unimplemented answers 501
 * NOT_IMPLEMENTED until its feature ticket replaces the body, and the request DTOs validate their
 * input. Runs with the real security chain, as {@link CON01ContractTest} does.
 */
@WebMvcTest(properties = {GitHubTestCredentials.CLIENT_ID, GitHubTestCredentials.CLIENT_SECRET})
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
  @MockitoBean private ConfigService configService;
  @MockitoBean private ProjectService projectService;
  @MockitoBean private SpecService specService;
  @MockitoBean private VcsProvider vcsProvider;
  @MockitoBean private LintRateLimiter lintRateLimiter;
  @MockitoBean private SpecHistoryService specHistory;
  @MockitoBean private ProjectAccess projectAccess;
  @MockitoBean private RunService runService;
  @MockitoBean private RunFilesService runFilesService;
  @MockitoBean private VersionService versionService;
  @MockitoBean private WebhookVerifier webhookVerifier;
  @MockitoBean private ProjectRepository projects;
  @MockitoBean private SecretCipher secretCipher;
  @MockitoBean private DeliveryDeduper deliveryDeduper;
  @MockitoBean private WebhookDispatcher webhookDispatcher;

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
}
