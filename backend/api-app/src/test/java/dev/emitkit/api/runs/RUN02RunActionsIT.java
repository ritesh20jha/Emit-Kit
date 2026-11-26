package dev.emitkit.api.runs;

import static dev.emitkit.domain.Role.ADMIN;
import static dev.emitkit.domain.Role.EDITOR;
import static dev.emitkit.domain.RunStatus.AWAITING_APPROVAL;
import static dev.emitkit.domain.RunStatus.CANCELLED;
import static dev.emitkit.domain.RunStatus.QUEUED;
import static dev.emitkit.domain.RunStatus.RUNNING;
import static dev.emitkit.domain.RunStatus.SUCCESS;
import static dev.emitkit.domain.RunTrigger.MANUAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.emitkit.api.GitHubTestCredentials;
import dev.emitkit.api.PostgresTestConfig;
import dev.emitkit.api.RabbitTestConfig;
import dev.emitkit.api.RedisTestConfig;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.messaging.ControlMessage;
import dev.emitkit.common.messaging.GenerationJob;
import dev.emitkit.common.messaging.Queues;
import dev.emitkit.domain.Decision;
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
import dev.emitkit.domain.RunApproval;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** RUN-02: cancelling, rerunning and approving runs. */
@SpringBootTest(
    properties = {
      "emitkit.vault.cipher=local",
      GitHubTestCredentials.CLIENT_ID,
      GitHubTestCredentials.CLIENT_SECRET
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class, RabbitTestConfig.class})
class RUN02RunActionsIT {

  /** Boot 4 does not wire Spring Security's MockMvc support, so the security chain needs this. */
  @TestConfiguration(proxyBeanMethods = false)
  static class SecurityMockMvcSupport {
    @Bean
    MockMvcBuilderCustomizer securityMockMvc() {
      return builder -> builder.apply(springSecurity());
    }
  }

  /** A queue bound to the control fanout, so the test can read what the API publishes there. */
  private static final String CONTROL_TAP = "run02.control-tap";

  private static final String CANCEL = "/api/v1/runs/{runId}/cancel";
  private static final String RERUN = "/api/v1/runs/{runId}/rerun";
  private static final String APPROVAL = "/api/v1/runs/{runId}/approval";
  private static final Instant BASE = Instant.parse("2026-03-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @Autowired private AmqpAdmin amqp;
  @Autowired private RabbitTemplate rabbit;
  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository organizations;
  @Autowired private OrgMemberRepository orgMembers;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository generationRuns;
  @Autowired private RunApprovalRepository approvals;
  @Autowired private OAuthAccountRepository oauth;

  @BeforeEach
  void resetData() {
    Queue tap = QueueBuilder.durable(CONTROL_TAP).build();
    amqp.declareQueue(tap);
    amqp.declareBinding(BindingBuilder.bind(tap).to(new FanoutExchange(Queues.CONTROL_EXCHANGE)));
    amqp.purgeQueue(CONTROL_TAP, false);
    amqp.purgeQueue(Queues.JOBS_QUEUE, false);
    approvals.deleteAll();
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
  void cancelSuccessRun_409() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f, SUCCESS, MANUAL, 1);

    mvc.perform(post(CANCEL, run.getId()).with(csrf()).with(signedInAs(f.owner())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(SUCCESS);
          assertThat(saved.isCancelRequested()).isFalse();
        });
  }

  @Test
  void cancelRunning_setsFlag_andPublishesControl() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f, RUNNING, MANUAL, 1);

    mvc.perform(post(CANCEL, run.getId()).with(csrf()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(RUNNING);
          assertThat(saved.isCancelRequested()).isTrue();
        });
    assertThat(rabbit.receiveAndConvert(CONTROL_TAP, 2000))
        .isEqualTo(new ControlMessage("CANCEL", run.getId()));
  }

  @Test
  void cancelQueued_isCancelledImmediately() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f, QUEUED, MANUAL, 1);

    mvc.perform(post(CANCEL, run.getId()).with(csrf()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(CANCELLED);
          assertThat(saved.isCancelRequested()).isTrue();
          assertThat(saved.getFinishedAt()).isNotNull();
        });
    assertThat(rabbit.receiveAndConvert(CONTROL_TAP, 2000))
        .isEqualTo(new ControlMessage("CANCEL", run.getId()));
  }

  @Test
  void rerun_copiesConfigVersion_setsParent() throws Exception {
    Fixture f = fixture();
    User editor = addMember(f.org(), 2, "editor", EDITOR);
    GenerationRun parent = run(f, SUCCESS, MANUAL, 1);
    config(f.project(), 2, f.owner());

    String body =
        mvc.perform(post(RERUN, parent.getId()).with(csrf()).with(signedInAs(editor)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.trigger").value("RERUN"))
            .andExpect(jsonPath("$.parentRunId").value(parent.getId()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String rerunId = JsonPath.read(body, "$.id");
    assertThat(generationRuns.findById(rerunId))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getConfigId()).isEqualTo(f.config().getId());
          assertThat(saved.getParentRunId()).isEqualTo(parent.getId());
          assertThat(saved.getTriggeredByUserId()).isEqualTo(editor.getId());
        });
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(rerunId, 0));
  }

  @Test
  void approve_reEnqueues() throws Exception {
    Fixture f = fixture();
    GenerationRun run = awaiting(f);

    decide(f.owner(), run, "{\"decision\":\"APPROVED\",\"comment\":\"ship it\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("QUEUED"))
        .andExpect(jsonPath("$.approval.decision").value("APPROVED"))
        .andExpect(jsonPath("$.approval.decidedBy").value("owner"));

    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(run.getId(), 0));
    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(QUEUED));
    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(a -> {
          assertThat(a.getDecision()).isEqualTo(Decision.APPROVED);
          assertThat(a.getDecidedBy()).isEqualTo(f.owner().getId());
          assertThat(a.getDecidedAt()).isNotNull();
        });
  }

  @Test
  void reject_cancels_withComment() throws Exception {
    Fixture f = fixture();
    GenerationRun run = awaiting(f);

    decide(f.owner(), run, "{\"decision\":\"REJECTED\",\"comment\":\"breaks clients\"}")
        .andExpect(status().isOk());

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(CANCELLED);
          assertThat(saved.getFailureCode()).isEqualTo("BREAKING_BLOCKED");
          assertThat(saved.getFailureMessage()).isEqualTo("Rejected: breaks clients");
          assertThat(saved.getFinishedAt()).isNotNull();
        });
  }

  @Test
  void editorApproving_403() throws Exception {
    Fixture f = fixture();
    User editor = addMember(f.org(), 2, "editor", EDITOR);
    GenerationRun run = awaiting(f);

    decide(editor, run, "{\"decision\":\"APPROVED\"}")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(AWAITING_APPROVAL));
    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(a -> assertThat(a.getDecision()).isNull());
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  @Test
  void approveTwice_409() throws Exception {
    Fixture f = fixture();
    GenerationRun run = awaiting(f);
    String approve = "{\"decision\":\"APPROVED\"}";
    decide(f.owner(), run, approve).andExpect(status().isOk());

    decide(f.owner(), run, approve)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
        .isEqualTo(new GenerationJob(run.getId(), 0));
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  /** Two approvals fired together over several runs: each run gets one 200, one 409 and one job. */
  @Test
  void concurrentApprovals_oneWins_oneJob() throws Exception {
    Fixture f = fixture();
    String approve = "{\"decision\":\"APPROVED\"}";
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      for (int round = 0; round < 8; round++) {
        GenerationRun run = awaiting(f);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> approveNow =
            () -> {
              go.await();
              return decide(f.owner(), run, approve).andReturn().getResponse().getStatus();
            };
        Future<Integer> first = pool.submit(approveNow);
        Future<Integer> second = pool.submit(approveNow);
        go.countDown();
        assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
            .containsExactlyInAnyOrder(200, 409);

        assertThat(generationRuns.findById(run.getId()))
            .hasValueSatisfying(saved -> assertThat(saved.getStatus()).isEqualTo(QUEUED));
        assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 2000))
            .isEqualTo(new GenerationJob(run.getId(), 0));
        assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
      }
    }
  }

  @Test
  void cancelAwaitingApproval_isCancelledAtOnce() throws Exception {
    Fixture f = fixture();
    GenerationRun run = awaiting(f);

    mvc.perform(post(CANCEL, run.getId()).with(csrf()).with(signedInAs(f.owner())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(CANCELLED);
          assertThat(saved.isCancelRequested()).isTrue();
          assertThat(saved.getFinishedAt()).isNotNull();
        });
  }

  @Test
  void rerunRunning_409_andNothingQueued() throws Exception {
    Fixture f = fixture();
    GenerationRun run = run(f, RUNNING, MANUAL, 1);

    mvc.perform(post(RERUN, run.getId()).with(csrf()).with(signedInAs(f.owner())))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    assertThat(generationRuns.count()).isEqualTo(1);
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  /** A user outside the project's org gets NOT_FOUND from every action, and changes nothing. */
  @Test
  void nonMember_cancelRerunApprove_getNotFound_andNothingChanges() throws Exception {
    Fixture f = fixture();
    GenerationRun run = awaiting(f);
    User stranger =
        users.save(new User(null, 9, "stranger", null, null, "https://avatars.example.com/u/9"));

    List<ResultActions> attempts =
        List.of(
            mvc.perform(post(CANCEL, run.getId()).with(csrf()).with(signedInAs(stranger))),
            mvc.perform(post(RERUN, run.getId()).with(csrf()).with(signedInAs(stranger))),
            decide(stranger, run, "{\"decision\":\"APPROVED\"}"));
    for (ResultActions attempt : attempts) {
      attempt
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.detail").value("run not found"));
    }

    assertThat(generationRuns.findAll()).hasSize(1);
    assertThat(generationRuns.findById(run.getId()))
        .hasValueSatisfying(saved -> {
          assertThat(saved.getStatus()).isEqualTo(AWAITING_APPROVAL);
          assertThat(saved.isCancelRequested()).isFalse();
        });
    assertThat(approvals.findByRunId(run.getId()))
        .hasValueSatisfying(a -> assertThat(a.getDecision()).isNull());
    assertThat(rabbit.receiveAndConvert(Queues.JOBS_QUEUE, 1000)).isNull();
  }

  /** A signed-in owner of an org, with one project that has a config at version 1. */
  private record Fixture(Organization org, User owner, Project project, ProjectConfig config) {}

  private Fixture fixture() {
    Organization acme =
        organizations.save(new Organization(null, null, "vcs-acme", "acme", "acme", false, null));
    User owner = addMember(acme, 1, "owner", ADMIN);
    Project project = project(acme, "acme/widgets", owner);
    return new Fixture(acme, owner, project, config(project, 1, owner));
  }

  private Project project(Organization org, String repo, User creator) {
    return projects.save(
        new Project(
            null, org.getId(), repo, "openapi.yaml", "main", OutputMode.APPEND, creator.getId()));
  }

  private ProjectConfig config(Project project, int version, User creator) {
    return configs.save(
        new ProjectConfig(
            null, project.getId(), version, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
  }

  /** A run created {@code second} seconds after {@link #BASE}, so the run history is known. */
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

  /** A run waiting at the breaking-change gate, with its approval row still undecided. */
  private GenerationRun awaiting(Fixture f) {
    GenerationRun run = run(f, AWAITING_APPROVAL, MANUAL, 1);
    approvals.saveAndFlush(new RunApproval(null, run.getId(), BASE));
    return run;
  }

  private ResultActions decide(User caller, GenerationRun run, String json) throws Exception {
    return mvc.perform(
        post(APPROVAL, run.getId())
            .with(csrf())
            .with(signedInAs(caller))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  /** Adds a user as a member of an org with the given role. */
  private User addMember(Organization org, long githubId, String login, Role role) {
    User user =
        users.save(
            new User(
                null, githubId, login, null, null, "https://avatars.example.com/u/" + githubId));
    orgMembers.save(new OrgMember(org.getId(), user.getId(), role, RoleSource.SYNC));
    return user;
  }

  private static RequestPostProcessor signedInAs(User user) {
    return authentication(
        new OAuth2AuthenticationToken(
            new EmitKitUser(user.getId(), user.getLogin()), List.of(), "github"));
  }
}
