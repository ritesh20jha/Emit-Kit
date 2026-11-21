package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.BREAKING_GATE;
import static dev.emitkit.domain.StepName.COMMIT_OUTPUT;
import static dev.emitkit.domain.StepName.FINALIZE;
import static dev.emitkit.domain.StepName.PACKAGE_ARTIFACT;
import static dev.emitkit.domain.StepName.RUN_GENERATORS;
import static dev.emitkit.domain.StepName.SYNTAX_CHECK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.Decision;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunApproval;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.spec.diff.Change;
import dev.emitkit.spec.diff.ChangeKind;
import dev.emitkit.spec.diff.OpChange;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineRunner;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepOutputs;
import dev.emitkit.worker.pipeline.StepResult;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * BREAKING_GATE against PostgreSQL 18. The step runs directly on a run whose diff DIFF_SPEC has
 * saved, as the pipeline leaves it before this step.
 */
@SpringBootTest(
    classes = WRK05BreakingGateTest.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "emitkit.vault.cipher=local"
    })
@Testcontainers
class WRK05BreakingGateTest {

  /** Two breaking changes: a removed operation and a removed parameter. One non-breaking change. */
  private static final SpecDiff DIFF =
      new SpecDiff(
          List.of(),
          List.of(new OpRef("deletePet", "DELETE", "/pets/{id}")),
          List.of(
              new OpChange(
                  new OpRef("listPets", "GET", "/pets"),
                  List.of(
                      new Change(ChangeKind.PARAM_REMOVED, "query.limit", true),
                      new Change(ChangeKind.PARAM_ADDED, "query.cursor", false)))));

  private static final int BREAKING = 2;

  /** Only non-breaking changes: an added operation and an added optional parameter. */
  private static final SpecDiff NON_BREAKING =
      new SpecDiff(
          List.of(new OpRef("createPet", "POST", "/pets")),
          List.of(),
          List.of(
              new OpChange(
                  new OpRef("listPets", "GET", "/pets"),
                  List.of(new Change(ChangeKind.PARAM_ADDED, "query.cursor", false)))));

  private static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private RunApprovalRepository approvals;
  @Autowired private PlatformTransactionManager transactions;

  @TempDir Path workRoot;

  private User creator;
  private Project project;
  private ProjectConfig config;
  private BreakingGateStep gate;

  @BeforeEach
  void seedProjectAndStep() {
    long n = SEQUENCE.incrementAndGet();
    creator =
        users.saveAndFlush(
            new User(null, n, "user" + n, "User " + n, "user" + n + "@example.com", "https://a"));
    Organization org =
        orgs.saveAndFlush(
            new Organization(null, "github", "org-" + n, "org" + n, "Org " + n, false, null));
    project =
        projects.saveAndFlush(
            new Project(
                null,
                org.getId(),
                "acme/api-" + n,
                "openapi.yaml",
                "main",
                OutputMode.APPEND,
                creator.getId()));
    config =
        configs.saveAndFlush(
            new ProjectConfig(
                null, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, creator.getId()));
    gate = new BreakingGateStep(runs, approvals, transactions);
  }

  @Test
  void allow_continues() {
    config.setBreakingGate(BreakingGate.ALLOW);
    String runId = runWithDiff(RunStatus.RUNNING).getId();

    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Continue(new BreakingGateStep.Output(BREAKING)));
    assertThat(approvalOf(runId)).isEmpty();
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void block_fails() {
    config.setBreakingGate(BreakingGate.BLOCK);
    String runId = runWithDiff(RunStatus.RUNNING).getId();

    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(
            new StepResult.Fail(ErrorCode.BREAKING_BLOCKED, "2 breaking changes; policy is BLOCK"));
    assertThat(approvalOf(runId)).isEmpty();
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void requireApproval_parksAndCreatesApproval() {
    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    String runId = runWithDiff(RunStatus.RUNNING).getId();
    RecordingLog log = new RecordingLog();

    assertThat(gate.execute(context(reload(runId), log))).isEqualTo(new StepResult.Park());
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    assertThat(approvalOf(runId))
        .hasValueSatisfying(approval -> assertThat(approval.getDecision()).isNull());
    assertThat(log.warnings).containsExactly("2 breaking changes; waiting for approval");
    assertThat(log.infos)
        .contains("Breaking: `DELETE /pets/{id} (deletePet)` removed")
        .contains("Breaking: `GET /pets (listPets)` param removed at `query.limit`");
    assertThat(log.flushes).isEqualTo(1);
  }

  /**
   * Drives a real PipelineRunner through a park and a resume. The first delivery parks at
   * BREAKING_GATE. After approval, the redelivery skips the steps with saved outputs, runs the real
   * gate, which continues, and reaches RUN_GENERATORS.
   */
  @Test
  void approvedResume_continuesToRunGenerators() {
    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    configs.saveAndFlush(config); // the runner loads the config from the database
    String runId = runWithDiff(RunStatus.RUNNING).getId();
    saveOutputsThrough(runId, StepName.CALC_VERSION);
    List<StepName> executed = new ArrayList<>();
    PipelineRunner runner = runner(executed);

    // First delivery: BREAKING_GATE parks the run, so nothing after it runs.
    runner.run(runId);
    assertThat(executed).containsExactly(BREAKING_GATE);
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);

    // The reviewer approves (RunService.decideApproval), which queues the run again.
    RunApproval approval = approvalOf(runId).orElseThrow();
    approval.decide(creator.getId(), Decision.APPROVED, null, T3);
    approvals.saveAndFlush(approval);
    GenerationRun queued = reload(runId);
    queued.setStatus(RunStatus.QUEUED);
    runs.saveAndFlush(queued);

    // Redelivery: the runner skips the steps with saved outputs, runs BREAKING_GATE again, which
    // now continues, and goes on to RUN_GENERATORS and the rest of the pipeline.
    executed.clear();
    runner.run(runId);

    assertThat(executed)
        .containsExactly(
            BREAKING_GATE, RUN_GENERATORS, SYNTAX_CHECK, PACKAGE_ARTIFACT, COMMIT_OUTPUT, FINALIZE);
    assertThat(
            StepOutputs.read(
                reload(runId).getStepOutputs(), BREAKING_GATE, BreakingGateStep.Output.class))
        .contains(new BreakingGateStep.Output(BREAKING));
    assertThat(approvalOf(runId))
        .hasValueSatisfying(saved -> assertThat(saved.getDecision()).isEqualTo(Decision.APPROVED));
  }

  @Test
  void redeliveredWhileAwaiting_createsNoSecondApproval() {
    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    String runId = runWithDiff(RunStatus.RUNNING).getId();

    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Park());
    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Park());

    assertThat(approvals.findAll())
        .filteredOn(approval -> runId.equals(approval.getRunId()))
        .hasSize(1);
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
  }

  /**
   * A real PipelineRunner over every step: the real BreakingGateStep, and stubs for the others.
   * Every step that runs is added to {@code executed}.
   */
  private PipelineRunner runner(List<StepName> executed) {
    List<PipelineStep> steps = new ArrayList<>();
    for (StepName name : StepName.values()) {
      steps.add(recording(name == BREAKING_GATE ? gate : stub(name), executed));
    }
    // No step in this test asks for VCS, so the provider is never called.
    VcsProvider unused =
        (VcsProvider)
            Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {VcsProvider.class},
                (proxy, method, args) -> {
                  throw new UnsupportedOperationException(method.getName());
                });
    return new PipelineRunner(
        steps,
        runs,
        projects,
        configs,
        unused,
        transactions,
        new CancellationRegistry(runs),
        id -> RunLogger.NOOP,
        InstantSource.system(),
        new WorkDir(workRoot));
  }

  /** Adds the step's name to {@code executed} when it runs, then runs the step. */
  private static PipelineStep recording(PipelineStep step, List<StepName> executed) {
    return new PipelineStep() {
      @Override
      public StepName name() {
        return step.name();
      }

      @Override
      public StepResult execute(RunContext ctx) throws Exception {
        executed.add(step.name());
        return step.execute(ctx);
      }
    };
  }

  /** A step that continues with a fixed output. */
  private static PipelineStep stub(StepName name) {
    return new PipelineStep() {
      @Override
      public StepName name() {
        return name;
      }

      @Override
      public StepResult execute(RunContext ctx) {
        return new StepResult.Continue(name.name() + "-out");
      }
    };
  }

  @Test
  void cancelRequested_throwsWithoutParking() {
    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    GenerationRun run = runWithDiff(RunStatus.RUNNING);
    run.setCancelRequested(true);
    runs.saveAndFlush(run);
    String runId = run.getId();

    assertThatThrownBy(() -> gate.execute(context(reload(runId), new RecordingLog())))
        .isInstanceOf(CancelledException.class);
    assertThat(approvalOf(runId)).isEmpty();
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void noBreakingChanges_continuesWithoutApproval() {
    String runId = runWith(NON_BREAKING, RunStatus.RUNNING).getId();

    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Continue(new BreakingGateStep.Output(0)));
    config.setBreakingGate(BreakingGate.BLOCK);
    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Continue(new BreakingGateStep.Output(0)));

    assertThat(approvalOf(runId)).isEmpty();
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.RUNNING);
  }

  @Test
  void terminalRun_parksWithoutWriting() {
    config.setBreakingGate(BreakingGate.REQUIRE_APPROVAL);
    String runId = runWithDiff(RunStatus.CANCELLED).getId();

    assertThat(gate.execute(context(reload(runId), new RecordingLog())))
        .isEqualTo(new StepResult.Park());
    assertThat(approvalOf(runId)).isEmpty();
    assertThat(reload(runId).getStatus()).isEqualTo(RunStatus.CANCELLED);
  }

  /** A RUNNING run of the project whose saved diff is {@link #DIFF}. */
  private GenerationRun runWithDiff(RunStatus status) {
    return runWith(DIFF, status);
  }

  /** A run of the project, in {@code status}, whose saved diff is {@code diff}. */
  private GenerationRun runWith(SpecDiff diff, RunStatus status) {
    GenerationRun run =
        new GenerationRun(null, project.getId(), config.getId(), RunTrigger.MANUAL, false, T3);
    run.setStatus(status);
    run.setDiffSummary(JSON.writeValueAsString(diff));
    return runs.saveAndFlush(run);
  }

  /** Saves a placeholder output for every step up to and including {@code last}. */
  private void saveOutputsThrough(String runId, StepName last) {
    GenerationRun run = reload(runId);
    String outputs = run.getStepOutputs();
    for (StepName name : StepName.values()) {
      if (name.compareTo(last) <= 0) {
        outputs = StepOutputs.with(outputs, name, name.name() + "-out");
      }
    }
    run.setStepOutputs(outputs);
    runs.saveAndFlush(run);
  }

  private Optional<RunApproval> approvalOf(String runId) {
    return approvals.findByRunId(runId);
  }

  private RunContext context(GenerationRun run, RunLogger log) {
    return new RunContext(
        run,
        project,
        config,
        () -> {
          throw new UnsupportedOperationException("no VCS in this step");
        },
        log,
        new CancellationRegistry(runs));
  }

  private GenerationRun reload(String runId) {
    return runs.findById(runId).orElseThrow();
  }

  /** Keeps the lines the step writes, so a test can read them. */
  private static final class RecordingLog implements RunLogger {
    final List<String> infos = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    int flushes;

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void error(String message) {}

    @Override
    public void mask(String secret) {}

    @Override
    public void step(StepName step) {}

    @Override
    public void flush() {
      flushes++;
    }

    @Override
    public void done() {}
  }

  /** Boots JPA and Flyway against the container, with the domain's entities and repositories. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
