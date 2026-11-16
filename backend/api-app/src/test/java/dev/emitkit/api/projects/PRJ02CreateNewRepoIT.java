package dev.emitkit.api.projects;

import static dev.emitkit.spec.lint.Severity.ERROR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.spec.lint.SpecLinter;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.util.List;
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
 * PRJ-02: creating a project with a new repository, seeded with a starter OpenAPI spec. Runs
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
class PRJ02CreateNewRepoIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String PROJECTS = "/api/v1/orgs/{orgId}/projects";

  @Autowired private MockMvc mvc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;

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
  }

  @Test
  void createsRepoAndStarterSpec() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(newBody("acme/pets-api", "null")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.repoFullName").value("acme/pets-api"))
        .andExpect(jsonPath("$.defaultBranch").value("main"))
        .andExpect(jsonPath("$.webhook.registered").value(true));

    assertThat(vcs.listRepos())
        .singleElement()
        .satisfies(repo -> {
          assertThat(repo.fullName()).isEqualTo("acme/pets-api");
          assertThat(repo.isPrivate()).isTrue();
        });
    assertThat(vcs.file("acme/pets-api", "openapi.yaml").content())
        .isEqualTo(ProjectService.starterSpec("pets-api"));

    Project saved = onlyProject(acme);
    assertThat(saved.getRepoFullName()).isEqualTo("acme/pets-api");
    assertThat(saved.getSpecPath()).isEqualTo("openapi.yaml");
    assertThat(saved.getDefaultBranch()).isEqualTo("main");
    assertThat(configs.findLatest(saved.getId()).orElseThrow().getVersion()).isEqualTo(1);
    assertThat(saved.getWebhookId()).isNotNull();
    assertThat(vcs.webhooks("acme/pets-api"))
        .singleElement()
        .satisfies(hook -> assertThat(hook.id()).isEqualTo(saved.getWebhookId()));
  }

  @Test
  void visibilityPublic_createsPublicRepo() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(newBody("acme/pets-api", "\"PUBLIC\"")))
        .andExpect(status().isCreated());

    assertThat(vcs.listRepos())
        .singleElement()
        .satisfies(repo -> assertThat(repo.isPrivate()).isFalse());
  }

  @Test
  void starterSpecParsesAndHasNoLintErrors() {
    String spec = ProjectService.starterSpec("pets-api");
    var parsed = new SpecParser().parse(spec);
    assertThat(parsed.ok()).isTrue();
    assertThat(new SpecLinter().lint(parsed, spec)).noneMatch(issue -> issue.severity() == ERROR);
  }

  @Test
  void nameTaken_returns409() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, Role.ADMIN);
    vcs.createRepo("acme", "pets-api", true);

    mvc.perform(
            post(PROJECTS, acme.getId())
                .with(csrf())
                .with(signedInAs(octo))
                .contentType("application/json")
                .content(newBody("acme/pets-api", "null")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"))
        .andExpect(jsonPath("$.detail").value("Repository name is taken"));

    assertThat(projects.count()).isZero();
    assertThat(configs.count()).isZero();
    assertThat(vcs.calls("putFile")).isEmpty();
    assertThat(vcs.webhooks("acme/pets-api")).isEmpty();
  }

  /** A create body for mode NEW. {@code visibility} is a JSON value: a quoted string, or null. */
  private static String newBody(String repo, String visibility) {
    return """
    {"mode":"NEW","repo":"%s","specPath":"openapi.yaml","defaultBranch":"main",\
    "visibility":%s,"outputMode":"APPEND","outputRepo":null}\
    """
        .formatted(repo, visibility);
  }

  private Project onlyProject(Organization org) {
    List<Project> saved = projects.findByOrgId(org.getId());
    assertThat(saved).hasSize(1);
    return saved.get(0);
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
