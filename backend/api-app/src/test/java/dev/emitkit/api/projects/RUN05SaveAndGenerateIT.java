package dev.emitkit.api.projects;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.Role.VIEWER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RabbitTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
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
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * RUN-05: saving a spec with {@code generate=true} commits it, then starts a manual run. A run that
 * cannot start leaves the commit in place and answers 202 with the reason in {@code runError}. Runs
 * against Postgres, Redis and RabbitMQ; the VCS provider is an in-memory fake.
 */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class, RabbitTestConfig.class})
class RUN05SaveAndGenerateIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SPEC_PATH = "openapi.yaml";
  private static final String SPEC =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.2.3
      paths: {}
      """;
  private static final String EDITED =
      """
      openapi: 3.1.0
      info:
        title: Pets
        version: 1.3.0
      paths: {}
      """;

  @Autowired private MockMvc mvc;
  @Autowired private AmqpAdmin amqp;
  @MockitoSpyBean private RabbitTemplate rabbit;
  @MockitoSpyBean private OrgAccessService orgAccess;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;

  @MockitoBean private VcsProvider vcsProvider;

  private InMemoryVcsClient vcs;

  @BeforeEach
  void resetData() {
    amqp.purgeQueue(Queues.JOBS_QUEUE, false);
    // Runs reference configs, so they go first.
    generationRuns.deleteAllInBatch();
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
    vcs = new InMemoryVcsClient();
    when(vcsProvider.forProject(anyString())).thenReturn(vcs);
    vcs.createRepo("acme", "api", false);
    vcs.putFile("acme/api", SPEC_PATH, SPEC, null, "add spec", "main");
  }

  @Test
  void bothSucceed_200WithRunId() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    Project project = project(acme, octo);
    config(project, octo);

    String body =
        save(project, octo, EDITED, GitBlob.sha(SPEC), true)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.newSha").value(GitBlob.sha(EDITED)))
            .andExpect(jsonPath("$.runError").value(nullValue()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String runId = JsonPath.read(body, "$.runId");
    assertThat(runId).isNotNull();
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
    GenerationRun run = generationRuns.findById(runId).orElseThrow();
    assertThat(run.getProjectId()).isEqualTo(project.getId());
    assertThat(run.getTrigger()).isEqualTo(RunTrigger.MANUAL);
    assertThat(run.isDryRun()).isFalse();
    assertThat(run.getTriggeredByUserId()).isEqualTo(octo.getId());
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(runId, 0));
  }

  @Test
  void enqueueFails_202WithRunError_commitKept() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    Project project = project(acme, octo);
    config(project, octo);
    doThrow(new AmqpConnectException(new IOException("broker unreachable")))
        .when(rabbit)
        .convertAndSend(
            eq(Queues.JOBS_EXCHANGE), eq(Queues.JOBS_ROUTING_KEY), any(GenerationJob.class));

    save(project, octo, EDITED, GitBlob.sha(SPEC), true)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.newSha").value(GitBlob.sha(EDITED)))
        .andExpect(jsonPath("$.runId").value(nullValue()))
        .andExpect(jsonPath("$.runError.code").value("INTERNAL"))
        .andExpect(jsonPath("$.runError.detail").value("Could not queue run"));

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
  }

  @Test
  void runStartFails_202WithCodeOfTheError() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    Project project = project(acme, octo);

    // No config yet, so the run cannot be created at all.
    save(project, octo, EDITED, GitBlob.sha(SPEC), true)
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.runId").value(nullValue()))
        .andExpect(jsonPath("$.runError.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.runError.detail").value("project has no config"));

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
  }

  @Test
  void generateFalse_noRun() throws Exception {
    Organization acme = org("acme");
    User octo = user(1, "octo");
    member(acme, octo, ADMIN);
    Project project = project(acme, octo);
    config(project, octo);

    save(project, octo, EDITED, GitBlob.sha(SPEC), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.runId").value(nullValue()))
        .andExpect(jsonPath("$.runError").value(nullValue()));

    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(EDITED);
    assertThat(generationRuns.findAll()).isEmpty();
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  @Test
  void viewerGenerate_403_nothingCommitted() throws Exception {
    Organization acme = org("acme");
    User viewer = user(1, "viewer");
    member(acme, viewer, VIEWER);
    Project project = project(acme, viewer);
    config(project, viewer);

    save(project, viewer, EDITED, GitBlob.sha(SPEC), true)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertNoSaveReachedVcs();
    assertThat(generationRuns.findAll()).isEmpty();
  }

  @Test
  void specEditWithoutRunTrigger_403_beforeCommit() throws Exception {
    // No organization role grants SPEC_EDIT without RUN_TRIGGER, so the denial is simulated on
    // the org check: the editor may save the spec but not start a run.
    Organization acme = org("acme");
    User editor = user(1, "editor");
    member(acme, editor, EDITOR);
    Project project = project(acme, editor);
    config(project, editor);
    doThrow(new AppException(ErrorCode.FORBIDDEN, "your role does not allow RUN_TRIGGER"))
        .when(orgAccess)
        .require(eq(acme.getId()), eq(Permission.RUN_TRIGGER));

    save(project, editor, EDITED, GitBlob.sha(SPEC), true)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertNoSaveReachedVcs();
    assertThat(generationRuns.findAll()).isEmpty();
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  /** Only the fixture write from {@link #resetData()} has reached the VCS, so no save did. */
  private void assertNoSaveReachedVcs() {
    assertThat(vcs.calls("putFile")).hasSize(1);
    assertThat(vcs.file("acme/api", SPEC_PATH).content()).isEqualTo(SPEC);
  }

  private ResultActions save(
      Project project, User user, String content, String sha, boolean generate) throws Exception {
    String body = JSON.writeValueAsString(new SaveSpecRequest(content, sha, null, generate));
    return mvc.perform(
        put("/api/v1/projects/{id}/spec", project.getId())
            .with(csrf())
            .with(signedInAs(user))
            .contentType("application/json")
            .content(body));
  }

  private Project project(Organization org, User user) {
    return projects.save(
        new Project(
            null, org.getId(), "acme/api", SPEC_PATH, "main", OutputMode.APPEND, user.getId()));
  }

  private ProjectConfig config(Project project, User user) {
    return configs.save(
        new ProjectConfig(null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, user.getId()));
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
