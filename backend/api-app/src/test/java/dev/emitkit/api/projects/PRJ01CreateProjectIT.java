package dev.emitkit.api.projects;

import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * PRJ-01: creating a project from an existing repository, the repo picker and the spec check. Runs
 * against Postgres and Redis, with the VCS provider replaced by an in-memory fake.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class PRJ01CreateProjectIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String PROJECTS = "/api/v1/orgs/{orgId}/projects";
  private static final String REPOS = "/api/v1/orgs/{orgId}/vcs-repos";
  private static final String SPEC =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.2.3
      paths: {}
      """;

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private SecretCipher cipher;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;

  @BeforeEach
  void resetData() {
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
    vcs.createRepo("acme", "api", false);
    vcs.putFile("acme/api", "openapi.yaml", SPEC, null, "add spec");
  }

  @Test
  void createExisting_savesProjectConfigAndWebhook() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(createBody("EXISTING", "acme/api", "openapi.yaml", "APPEND", null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.repoFullName").value("acme/api"))
        .andExpect(jsonPath("$.webhook.registered").value(true))
        .andExpect(jsonPath("$.webhook.webhookId").isNumber());

    Project saved = onlyProject(acme);
    assertThat(saved.getWebhookId()).isNotNull();
    ProjectConfig config = configs.findLatest(saved.getId()).orElseThrow();
    assertThat(config.getVersion()).isEqualTo(1);
    assertThat(config.getOutputs()).containsExactly(OutputKind.SDK);
    assertThat(config.getSdkLanguages()).containsExactly("typescript");
    assertThat(vcs.webhooks("acme/api"))
        .singleElement()
        .satisfies(hook -> {
          assertThat(hook.id()).isEqualTo(saved.getWebhookId());
          assertThat(hook.url()).isEqualTo("http://localhost:8080/webhooks/github");
        });
  }

  @Test
  void webhookSecretStoredEncrypted_withProjectContext() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    createExisting(acme, octo, "acme/api");

    Project saved = onlyProject(acme);
    String encrypted = saved.getWebhookSecretEnc();
    assertThat(encrypted).isNotBlank();
    String secret =
        cipher.decrypt(
            encrypted, new SecretContext("projects", "webhook_secret_enc", saved.getId()));
    assertThat(secret).matches("[0-9a-f]{64}");
    assertThat(encrypted).doesNotContain(secret);
    assertThatThrownBy(() ->
            cipher.decrypt(
                encrypted,
                new SecretContext("projects", "webhook_secret_enc", "01OTHERPROJECT000000000000")))
        .isInstanceOf(AppException.class);
  }

  @Test
  void duplicateRepo_returns409() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    createExisting(acme, octo, "acme/api");

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(createBody("EXISTING", "acme/api", "openapi.yaml", "APPEND", null)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    assertThat(projects.count()).isEqualTo(1);
    assertThat(vcs.webhooks("acme/api")).hasSize(1);
  }

  @Test
  void specMissing_returns404SpecNotFound() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(createBody("EXISTING", "acme/api", "missing.yaml", "APPEND", null)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPEC_NOT_FOUND"));

    assertThat(projects.count()).isZero();
    assertThat(vcs.webhooks("acme/api")).isEmpty();
  }

  @Test
  void webhookRegistrationFails_projectNotKept() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    vcs.failNext("registerWebhook", new VcsAuthException("no hook scope"));

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(createBody("EXISTING", "acme/api", "openapi.yaml", "APPEND", null)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("VCS_AUTH"));

    assertThat(projects.count()).isZero();
    assertThat(configs.count()).isZero();
    assertThat(vcs.webhooks("acme/api")).isEmpty();
  }

  @Test
  void separateModeWithoutOutputRepo_400() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    vcs.createRepo("acme", "out", true);

    for (String outputRepo : new String[] {"null", "\" \"", "\"acme/missing\""}) {
      mvc.perform(
              post(PROJECTS, acme.getId())
                  .with(csrf())
                  .with(signedInAs(octo))
                  .contentType("application/json")
                  .content(
                      createBody("EXISTING", "acme/api", "openapi.yaml", "SEPARATE", outputRepo)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
    assertThat(projects.count()).isZero();

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(
                    createBody("EXISTING", "acme/api", "openapi.yaml", "SEPARATE", "\"acme/out\"")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.outputRepoFullName").value("acme/out"));
    assertThat(onlyProject(acme).getOutputRepoFullName()).isEqualTo("acme/out");
  }

  @Test
  void specCheck_reportsTitleAndVersion() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            get(REPOS + "/{owner}/{repo}/spec-check", acme.getId(), "acme", "api")
                .param("path", "openapi.yaml")
                .with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(true))
        .andExpect(jsonPath("$.valid").value(true))
        .andExpect(jsonPath("$.title").value("Pets"))
        .andExpect(jsonPath("$.version").value("1.2.3"))
        .andExpect(jsonPath("$.errors.length()").value(0));
  }

  @Test
  void specCheck_missingFile_reportsNotFound() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            get(REPOS + "/{owner}/{repo}/spec-check", acme.getId(), "acme", "api")
                .param("path", "missing.yaml")
                .with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.valid").value(false))
        .andExpect(jsonPath("$.title").doesNotExist());
  }

  @Test
  void repoPicker_listsAndFiltersRepos() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    vcs.createRepo("acme", "web", true);

    mvc.perform(get(REPOS, acme.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2));

    mvc.perform(get(REPOS, acme.getId()).param("q", "WEB").with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].fullName").value("acme/web"))
        .andExpect(jsonPath("$[0].owner").value("acme"))
        .andExpect(jsonPath("$[0].isPrivate").value(true))
        .andExpect(jsonPath("$[0].defaultBranch").value("main"));
  }

  @Test
  void editorCanCreate_viewerCannot() throws Exception {
    Organization acme = org("acme");
    User viewer = user(1, "viewer");
    member(acme, viewer, VIEWER);
    User editor = user(2, "editor");
    member(acme, editor, EDITOR);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(viewer))
                .contentType("application/json")
                .content(createBody("EXISTING", "acme/api", "openapi.yaml", "APPEND", null)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    assertThat(projects.count()).isZero();
    assertThat(vcs.webhooks("acme/api")).isEmpty();

    createExisting(acme, editor, "acme/api");
    assertThat(projects.count()).isEqualTo(1);
    assertThat(vcs.webhooks("acme/api")).hasSize(1);
  }

  private void createExisting(Organization org, User user, String repo) throws Exception {
    mvc.perform(
            post(PROJECTS, org.getId())
                .with(csrf())
                .with(signedInAs(user))
                .contentType("application/json")
                .content(createBody("EXISTING", repo, "openapi.yaml", "APPEND", null)))
        .andExpect(status().isCreated());
  }

  private Project onlyProject(Organization org) {
    var saved = projects.findByOrgId(org.getId());
    assertThat(saved).hasSize(1);
    return saved.get(0);
  }

  /** A create body. {@code outputRepo} is a JSON value: a quoted string, or {@code null}. */
  private static String createBody(
      String mode, String repo, String specPath, String outputMode, @Nullable String outputRepo) {
    return """
    {"mode":"%s","repo":"%s","specPath":"%s","defaultBranch":"main",\
    "outputMode":"%s","outputRepo":%s}\
    """
        .formatted(mode, repo, specPath, outputMode, outputRepo == null ? "null" : outputRepo);
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }

  private User user(long githubId, String login) {
    return users.save(
        new User(null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
  }

  private Organization org(String slug) {
    return organizations.save(new Organization(null, null, "vcs-" + slug, slug, slug, false, null));
  }

  private void member(Organization org, User user, Role role) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
  }
}
