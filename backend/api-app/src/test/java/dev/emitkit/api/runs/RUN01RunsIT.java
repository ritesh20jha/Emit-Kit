package dev.emitkit.api.runs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.Role.VIEWER;
import static dev.emitkit.domain.RunStatus.FAILED;
import static dev.emitkit.domain.RunStatus.QUEUED;
import static dev.emitkit.domain.RunStatus.RUNNING;
import static dev.emitkit.domain.RunStatus.SUCCESS;
import static dev.emitkit.domain.RunTrigger.MANUAL;
import static dev.emitkit.domain.RunTrigger.RERUN;
import static dev.emitkit.domain.RunTrigger.WEBHOOK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RabbitTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintResult;
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
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** RUN-01: starting a run enqueues its job after commit, and runs are listed and read. */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class, RabbitTestConfig.class})
class RUN01RunsIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  /** The API only publishes; the test declares the jobs queue it reads back. */
  @TestConfiguration(proxyBeanMethods = false)
  static class JobTopology {
    @Bean
    DirectExchange jobsExchange() {
      return new DirectExchange(Queues.JOBS_EXCHANGE);
    }

    @Bean
    Queue jobsQueue() {
      return QueueBuilder.durable(Queues.JOBS_QUEUE).build();
    }

    @Bean
    Binding jobsBinding(Queue jobsQueue, DirectExchange jobsExchange) {
      return BindingBuilder.bind(jobsQueue).to(jobsExchange).with(Queues.JOBS_ROUTING_KEY);
    }
  }

  private static final String RUNS = "/api/v1/projects/{id}/runs";
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private RunService runs;
  @Autowired private AmqpAdmin amqp;
  @MockitoSpyBean private RabbitTemplate rabbit;
  @Autowired private PlatformTransactionManager transactions;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @Autowired private GenerationRunLogRepository logs;
  @Autowired private LintResultRepository lintResults;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    amqp.purgeQueue(Queues.JOBS_QUEUE, false);
    logs.deleteAll();
    lintResults.deleteAll();
    // One statement, so a rerun row and its parent are deleted together.
    generationRuns.deleteAllInBatch();
    configs.deleteAll();
    projects.deleteAll();
    orgMembers.deleteAll();
    organizations.deleteAll();
    oauth.deleteAll();
    users.deleteAll();
  }

  @Test
  void create_publishesJobAfterCommit() {
    Fixture f = fixture();

    GenerationRun run = runs.create(f.project().getId(), MANUAL, false, f.owner().getId(), null);

    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(run.getId(), 0));
    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(QUEUED));
  }

  @Test
  void rollback_publishesNothing() {
    Fixture f = fixture();
    TransactionTemplate transaction = new TransactionTemplate(transactions);

    assertThatThrownBy(() ->
            transaction.executeWithoutResult(status -> {
              runs.create(f.project().getId(), MANUAL, false, f.owner().getId(), null);
              throw new IllegalStateException("caller failed after create");
            }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
    assertThat(generationRuns.findAll()).isEmpty();
  }

  @Test
  void publishFailure_marksRunFailedAndAnswersInternal() {
    Fixture f = fixture();
    doThrow(new AmqpConnectException(new IOException("broker unreachable")))
        .when(rabbit)
        .convertAndSend(
            eq(Queues.JOBS_EXCHANGE), eq(Queues.JOBS_ROUTING_KEY), any(GenerationJob.class));

    assertThatThrownBy(
            () -> runs.create(f.project().getId(), MANUAL, false, f.owner().getId(), null))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL);
              assertThat(e.getMessage()).isEqualTo("Could not queue run");
            });

    List<GenerationRun> saved = generationRuns.findAll();
    assertThat(saved).hasSize(1);
    assertThat(saved.getFirst().getStatus()).isEqualTo(FAILED);
    assertThat(saved.getFirst().getFailureCode()).isEqualTo("INTERNAL");
    assertThat(saved.getFirst().getFailureMessage()).isEqualTo("Could not queue run");
    assertThat(saved.getFirst().getFinishedAt()).isNotNull();
  }

  @Test
  void create_usesLatestConfig() {
    Fixture f = fixture();
    ProjectConfig latest = config(f.project(), 2, f.owner());

    GenerationRun run = runs.create(f.project().getId(), MANUAL, false, f.owner().getId(), null);

    assertThat(run.getConfigId()).isEqualTo(latest.getId());
  }

  @Test
  void rerun_usesParentConfig_notLatest() {
    Fixture f = fixture();
    GenerationRun parent = run(f, SUCCESS, MANUAL, 1);
    config(f.project(), 2, f.owner());

    GenerationRun rerun =
        runs.create(f.project().getId(), RERUN, false, f.owner().getId(), parent.getId());

    assertThat(rerun.getConfigId()).isEqualTo(f.config().getId());
    assertThat(rerun.getParentRunId()).isEqualTo(parent.getId());
  }

  @Test
  void rerun_parentFromAnotherProject_isNotFound() {
    Fixture f = fixture();
    Project other = project(f.org(), f.owner(), "acme/gadgets");
    ProjectConfig otherConfig = config(other, 1, f.owner());
    GenerationRun parent =
        generationRuns.saveAndFlush(
            new GenerationRun(null, other.getId(), otherConfig.getId(), MANUAL, false, BASE));

    assertThatThrownBy(
            () -> runs.create(f.project().getId(), RERUN, false, f.owner().getId(), parent.getId()))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
              assertThat(e.getMessage()).isEqualTo("parent run not found");
            });
  }

  @Test
  void create_projectWithoutConfig_isNotFound() {
    Fixture f = fixture();
    Project bare = project(f.org(), f.owner(), "acme/gadgets");

    assertThatThrownBy(() -> runs.create(bare.getId(), MANUAL, false, f.owner().getId(), null))
        .isInstanceOfSatisfying(
            AppException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND);
              assertThat(e.getMessage()).isEqualTo("project has no config");
            });
  }

  @Test
  void startRun_viaEndpoint_returns201AndEnqueues() throws Exception {
    Fixture f = fixture();
    User editor = user(2, "editor");
    member(f.org(), editor, EDITOR);

    String body =
        mvc.perform(
                post(RUNS, f.project().getId())
                    .with(csrf())
                    .with(signedInAs(editor))
                    .contentType("application/json")
                    .content("{\"dryRun\":true}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.dryRun").value(true))
            .andExpect(jsonPath("$.trigger").value("MANUAL"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String runId = JsonPath.read(body, "$.id");
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(runId, 0));
    assertThat(generationRuns.findById(runId))
        .hasValueSatisfying(
            saved -> assertThat(saved.getTriggeredByUserId()).isEqualTo(editor.getId()));
  }

  @Test
  void list_filtersByStatusAndTrigger_cursorPaginates() throws Exception {
    Fixture f = fixture();
    // Five QUEUED MANUAL runs, interleaved with runs that differ in status or trigger.
    GenerationRun m2 = run(f, QUEUED, MANUAL, 2);
    run(f, SUCCESS, MANUAL, 1);
    GenerationRun m4 = run(f, QUEUED, MANUAL, 4);
    run(f, QUEUED, WEBHOOK, 3);
    GenerationRun m6 = run(f, QUEUED, MANUAL, 6);
    run(f, RUNNING, MANUAL, 5);
    GenerationRun m8 = run(f, QUEUED, MANUAL, 8);
    run(f, FAILED, MANUAL, 7);
    GenerationRun m10 = run(f, QUEUED, MANUAL, 10);
    run(f, QUEUED, RERUN, 9);

    String first = listRuns(f, "status=QUEUED&trigger=MANUAL&limit=2");
    assertThat(ids(first)).containsExactly(m10.getId(), m8.getId());
    String second = listRuns(f, "status=QUEUED&trigger=MANUAL&limit=2&cursor=" + nextCursor(first));
    assertThat(ids(second)).containsExactly(m6.getId(), m4.getId());
    String third = listRuns(f, "status=QUEUED&trigger=MANUAL&limit=2&cursor=" + nextCursor(second));
    assertThat(ids(third)).containsExactly(m2.getId());
    assertThat(JsonPath.<String>read(third, "$.nextCursor")).isNull();
  }

  @Test
  void get_includesSteps_andLintCounts() throws Exception {
    Fixture f = fixture();
    GenerationRun run =
        new GenerationRun(null, f.project().getId(), f.config().getId(), MANUAL, false, BASE);
    run.setStatus(RUNNING);
    run.setCurrentStep(StepName.RUN_GENERATORS);
    generationRuns.saveAndFlush(run);
    String id = run.getId();
    logLine(id, BASE, null, "run started");
    logLine(id, BASE, StepName.FETCH_SPEC, "fetching");
    logLine(id, BASE.plusMillis(2000), StepName.FETCH_SPEC, "fetched");
    logLine(id, BASE.plusMillis(2500), StepName.PARSE_SPEC, "parsing");
    logLine(id, BASE.plusMillis(3000), StepName.PARSE_SPEC, "parsed");
    logLine(id, BASE.plusMillis(3100), StepName.LINT_SPEC, "linting");
    logLine(id, BASE.plusMillis(3200), StepName.DIFF_SPEC, "diffing");
    logLine(id, BASE.plusMillis(3300), StepName.CALC_VERSION, "versioning");
    logLine(id, BASE.plusMillis(3400), StepName.BREAKING_GATE, "gating");
    logLine(id, BASE.plusMillis(3500), StepName.RUN_GENERATORS, "generating");
    lintResults.save(new LintResult(null, id, "rule-a", Severity.ERROR, "$.paths", "bad path"));
    lintResults.save(new LintResult(null, id, "rule-b", Severity.ERROR, "$.info", "bad info"));
    lintResults.save(new LintResult(null, id, "rule-c", Severity.WARNING, "$.tags", "odd tag"));

    String body =
        mvc.perform(get("/api/v1/runs/{runId}", id).with(signedInAs(f.owner())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(id))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.currentStep").value("RUN_GENERATORS"))
            .andExpect(jsonPath("$.steps[0].name").value("FETCH_SPEC"))
            .andExpect(jsonPath("$.steps[0].durationMs").value(2000))
            .andExpect(jsonPath("$.steps[1].durationMs").value(500))
            .andExpect(jsonPath("$.steps[7].durationMs").doesNotExist())
            .andExpect(jsonPath("$.lint.errors").value(2))
            .andExpect(jsonPath("$.lint.warnings").value(1))
            .andExpect(jsonPath("$.lint.infos").value(0))
            .andExpect(jsonPath("$.approval").doesNotExist())
            .andExpect(jsonPath("$.artifactAvailable").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(body, "$.steps[*].status"))
        .containsExactly(
            "DONE", "DONE", "DONE", "DONE", "DONE", "DONE", "RUNNING", "PENDING", "PENDING",
            "PENDING", "PENDING");
  }

  @Test
  void viewerTrigger_403() throws Exception {
    Fixture f = fixture();
    User viewer = user(2, "viewer");
    member(f.org(), viewer, VIEWER);

    mvc.perform(
            post(RUNS, f.project().getId())
                .with(csrf())
                .with(signedInAs(viewer))
                .contentType("application/json")
                .content("{\"dryRun\":false}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(generationRuns.findAll()).isEmpty();
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  /** A signed-in owner of an org, with one project that has a config at version 1. */
  private record Fixture(Organization org, User owner, Project project, ProjectConfig config) {}

  private Fixture fixture() {
    Organization acme =
        organizations.save(new Organization(null, null, "vcs-acme", "acme", "acme", false, null));
    User owner = user(1, "owner");
    member(acme, owner, ADMIN);
    Project project = project(acme, owner, "acme/widgets");
    return new Fixture(acme, owner, project, config(project, 1, owner));
  }

  private Project project(Organization org, User creator, String repo) {
    return projects.save(
        new Project(
            null, org.getId(), repo, "openapi.yaml", "main", OutputMode.APPEND, creator.getId()));
  }

  private ProjectConfig config(Project project, int version, User creator) {
    return configs.save(
        new ProjectConfig(
            null, project.getId(), version, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
  }

  /** A run created {@code second} seconds after {@link #BASE}, so the list order is known. */
  private GenerationRun run(Fixture f, RunStatus status, RunTrigger trigger, int second) {
    GenerationRun run =
        new GenerationRun(
            null,
            f.project().getId(),
            f.config().getId(),
            trigger,
            false,
            BASE.plusSeconds(second));
    run.setStatus(status);
    return generationRuns.saveAndFlush(run);
  }

  private String listRuns(Fixture f, String query) throws Exception {
    return mvc.perform(get(RUNS + "?" + query, f.project().getId()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static List<String> ids(String page) {
    return JsonPath.read(page, "$.items[*].id");
  }

  private static String nextCursor(String page) {
    return JsonPath.read(page, "$.nextCursor");
  }

  /** Appends a log line to a run; the sequence number follows the rows already written. */
  private void logLine(String runId, Instant ts, @Nullable StepName step, String message) {
    int seq = logs.findByRunIdAndSeqGreaterThanOrderBySeq(runId, -1).size() + 1;
    logs.save(new GenerationRunLog(runId, seq, ts, "INFO", step, message));
  }

  private User user(long githubId, String login) {
    return users.save(
        new User(null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
  }

  private void member(Organization org, User user, Role role) {
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }
}
