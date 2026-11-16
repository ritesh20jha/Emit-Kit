package dev.emitkit.api.projects;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsServerException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * PRJ-03: reading, listing and deleting projects, and re-registering a project's push webhook. Runs
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
class PRJ03ProjectCrudIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final String PROJECTS = "/api/v1/orgs/{orgId}/projects";
  private static final String PROJECT = "/api/v1/projects/{id}";
  private static final String WEBHOOK = "/api/v1/projects/{id}/webhook";
  private static final String REPO = "acme/api";
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");
  private static final String SPEC =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.2.3
      paths: {}
      """;

  @Autowired private MockMvc mvc;
  @Autowired private JdbcClient jdbc;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @Autowired private GenerationRunLogRepository logs;
  @Autowired private LintResultRepository lintResults;
  @Autowired private SecretCipher cipher;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;
  private @Nullable ListAppender<ILoggingEvent> captured;

  @BeforeEach
  void resetData() {
    logs.deleteAll();
    lintResults.deleteAll();
    generationRuns.deleteAllInBatch();
    configs.deleteAll();
    // Raw SQL, so soft-deleted rows go too; the entity hides them from its own queries.
    jdbc.sql("delete from projects").update();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
    vcs.createRepo("acme", "api", false);
    vcs.putFile("acme/api", "openapi.yaml", SPEC, null, "add spec");
  }

  @AfterEach
  void stopCapturingLogs() {
    if (captured != null) {
      Logger logger = (Logger) LoggerFactory.getLogger(ProjectService.class);
      logger.detachAppender(captured);
      captured.stop();
      captured = null;
    }
  }

  @Test
  void delete_removesWebhook_andHidesProject() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    assertThat(vcs.webhooks(REPO)).hasSize(1);

    mvc.perform(delete(PROJECT, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNoContent());

    assertThat(vcs.webhooks(REPO)).isEmpty();
    assertThat(isSoftDeleted(project.getId())).isTrue();
    mvc.perform(get(PROJECTS, acme.getId()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(0));
  }

  @Test
  void delete_webhookFails_stillDeletes_logsWarning() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    vcs.failNext("deleteWebhook", new VcsServerException("hook removal failed"));
    ListAppender<ILoggingEvent> logs = captureLogs();

    mvc.perform(delete(PROJECT, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNoContent());

    assertThat(logs.list)
        .filteredOn(event -> event.getLevel() == Level.WARN)
        .singleElement()
        .satisfies(event -> {
          assertThat(event.getLevel()).isEqualTo(Level.WARN);
          assertThat(event.getFormattedMessage()).contains(project.getId());
        });
    assertThat(isSoftDeleted(project.getId())).isTrue();
    mvc.perform(get(PROJECT, project.getId()).with(signedInAs(octo)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void deletedProjectGives404() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    mvc.perform(delete(PROJECT, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNoContent());

    mvc.perform(get(PROJECT, project.getId()).with(signedInAs(octo)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(post(WEBHOOK, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNotFound());
    mvc.perform(delete(PROJECT, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNotFound());
  }

  @Test
  void deletedRepo_canBeConnectedAgain() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    String firstId = onlyProject(acme).getId();
    mvc.perform(delete(PROJECT, firstId).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isNoContent());

    createExisting(acme, octo, REPO);

    Project second = onlyProject(acme);
    assertThat(second.getId()).isNotEqualTo(firstId);
    assertThat(second.getRepoFullName()).isEqualTo(REPO);
  }

  @Test
  void reRegister_rotatesSecret() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    String before = secretOf(project.getId());
    long oldWebhookId = project.getWebhookId();

    mvc.perform(post(WEBHOOK, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered").value(true))
        .andExpect(jsonPath("$.webhookId").isNumber());

    Project after = projects.findById(project.getId()).orElseThrow();
    assertThat(secretOf(project.getId())).isNotEqualTo(before).matches("[0-9a-f]{64}");
    assertThat(after.getWebhookId()).isNotEqualTo(oldWebhookId);
    assertThat(vcs.webhooks(REPO))
        .singleElement()
        .satisfies(hook -> assertThat(hook.id()).isEqualTo(after.getWebhookId()));
  }

  @Test
  void reRegister_oldHookAlreadyGone_registersNew() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    vcs.deleteWebhook(REPO, project.getWebhookId());

    mvc.perform(post(WEBHOOK, project.getId()).with(csrf()).with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.registered").value(true));

    assertThat(vcs.webhooks(REPO)).hasSize(1);
  }

  @Test
  void list_paginatesAndFilters() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    saveProject(acme, octo, "acme/api-one");
    saveProject(acme, octo, "acme/api-two");
    saveProject(acme, octo, "acme/web");

    String first =
        mvc.perform(get(PROJECTS, acme.getId()).param("limit", "2").with(signedInAs(octo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andExpect(jsonPath("$.nextCursor").isString())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String cursor = JsonPath.read(first, "$.nextCursor");
    String second =
        mvc.perform(
                get(PROJECTS, acme.getId())
                    .param("limit", "2")
                    .param("cursor", cursor)
                    .with(signedInAs(octo)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.nextCursor").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    List<String> everyone = new ArrayList<>(names(first));
    everyone.addAll(names(second));
    assertThat(everyone).containsExactlyInAnyOrder("acme/api-one", "acme/api-two", "acme/web");

    assertThat(namesFor(acme, octo, "api"))
        .containsExactlyInAnyOrder("acme/api-one", "acme/api-two");
    assertThat(namesFor(acme, octo, "API"))
        .containsExactlyInAnyOrder("acme/api-one", "acme/api-two");
    assertThat(namesFor(acme, octo, "_")).isEmpty();
    assertThat(namesFor(acme, octo, "%")).isEmpty();

    mvc.perform(get(PROJECTS, acme.getId()).param("cursor", "not-a-cursor").with(signedInAs(octo)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void list_includesLastRunStatus() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    Project api = saveProject(acme, octo, "acme/api");
    Project dry = saveProject(acme, octo, "acme/dry");
    saveProject(acme, octo, "acme/idle");
    ProjectConfig apiConfig = configs.save(DefaultConfig.version1(api.getId(), octo.getId()));
    ProjectConfig dryConfig = configs.save(DefaultConfig.version1(dry.getId(), octo.getId()));
    run(api, apiConfig, false, RunStatus.SUCCESS, BASE, "1.0.0");
    run(api, apiConfig, false, RunStatus.FAILED, BASE.plusSeconds(60), null);
    run(dry, dryConfig, false, RunStatus.SUCCESS, BASE, "1.1.0");
    run(dry, dryConfig, true, RunStatus.SUCCESS, BASE.plusSeconds(60), "2.0.0");

    String body =
        mvc.perform(get(PROJECTS, acme.getId()).with(signedInAs(octo)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    Map<String, Object> apiItem = item(body, "acme/api");
    assertThat(apiItem.get("lastRunStatus")).isEqualTo("FAILED");
    assertThat(Instant.parse((String) apiItem.get("lastRunAt"))).isEqualTo(BASE.plusSeconds(60));
    assertThat(apiItem.get("currentVersion")).isEqualTo("1.0.0");

    Map<String, Object> dryItem = item(body, "acme/dry");
    assertThat(dryItem.get("lastRunStatus")).isEqualTo("SUCCESS");
    assertThat(dryItem.get("currentVersion")).isEqualTo("1.1.0");

    Map<String, Object> idleItem = item(body, "acme/idle");
    assertThat(idleItem.get("lastRunStatus")).isNull();
    assertThat(idleItem.get("lastRunAt")).isNull();
    assertThat(idleItem.get("currentVersion")).isNull();

    mvc.perform(get(PROJECTS, acme.getId()).param("status", "FAILED").with(signedInAs(octo)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].repoFullName").value("acme/api"));
  }

  @Test
  void viewerCanRead_butCannotDeleteOrReRegister() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    createExisting(acme, octo, REPO);
    Project project = onlyProject(acme);
    User viewer = user(2, "viewer");
    member(acme, viewer, VIEWER);

    mvc.perform(get(PROJECT, project.getId()).with(signedInAs(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(project.getId()));
    mvc.perform(delete(PROJECT, project.getId()).with(csrf()).with(signedInAs(viewer)))
        .andExpect(status().isForbidden());
    mvc.perform(post(WEBHOOK, project.getId()).with(csrf()).with(signedInAs(viewer)))
        .andExpect(status().isForbidden());
    assertThat(isSoftDeleted(project.getId())).isFalse();
  }

  private List<String> namesFor(Organization org, User user, String q) throws Exception {
    String body =
        mvc.perform(get(PROJECTS, org.getId()).param("q", q).with(signedInAs(user)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return names(body);
  }

  private static List<String> names(String body) {
    List<Map<String, Object>> items = JsonPath.read(body, "$.items");
    return items.stream().map(item -> (String) item.get("repoFullName")).toList();
  }

  private static Map<String, Object> item(String body, String repo) {
    List<Map<String, Object>> items = JsonPath.read(body, "$.items");
    return items.stream()
        .filter(item -> repo.equals(item.get("repoFullName")))
        .findFirst()
        .orElseThrow();
  }

  /** Captures the events logged by the project service from now until the test ends. */
  private ListAppender<ILoggingEvent> captureLogs() {
    Logger logger = (Logger) LoggerFactory.getLogger(ProjectService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    captured = appender;
    return appender;
  }

  private void run(
      Project project,
      ProjectConfig config,
      boolean dryRun,
      RunStatus status,
      Instant createdAt,
      @Nullable String sdkVersion) {
    GenerationRun run =
        new GenerationRun(
            null, project.getId(), config.getId(), RunTrigger.MANUAL, dryRun, createdAt);
    run.setStatus(status);
    run.setSdkVersion(sdkVersion);
    generationRuns.save(run);
  }

  private boolean isSoftDeleted(String projectId) {
    return jdbc.sql("select count(*) from projects where id = :id and deleted_at is not null")
            .param("id", projectId)
            .query(Long.class)
            .single()
        == 1L;
  }

  private String secretOf(String projectId) {
    Project saved = projects.findById(projectId).orElseThrow();
    return cipher.decrypt(
        saved.getWebhookSecretEnc(),
        new SecretContext("projects", "webhook_secret_enc", saved.getId()));
  }

  private void createExisting(Organization org, User user, String repo) throws Exception {
    mvc.perform(
            post(PROJECTS, org.getId())
                .with(csrf())
                .with(signedInAs(user))
                .contentType("application/json")
                .content(createBody(repo)))
        .andExpect(status().isCreated());
  }

  private static String createBody(String repo) {
    return """
    {"mode":"EXISTING","repo":"%s","specPath":"openapi.yaml","defaultBranch":"main",\
    "outputMode":"APPEND","outputRepo":null}\
    """
        .formatted(repo);
  }

  private Project saveProject(Organization org, User user, String repo) {
    return projects.save(
        new Project(
            null, org.getId(), repo, "openapi.yaml", "main", OutputMode.APPEND, user.getId()));
  }

  private Project onlyProject(Organization org) {
    var saved = projects.findByOrgId(org.getId());
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
