package dev.emitkit.worker.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintFailOn;
import dev.emitkit.domain.LintResult;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * LINT_SPEC, DIFF_SPEC and CALC_VERSION against PostgreSQL 18. Each step runs directly on a run
 * that already points at a stored snapshot, as the pipeline leaves it after PARSE_SPEC.
 */
@SpringBootTest(
    classes = WRK04LintDiffVersionTest.TestApp.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.flyway.enabled=true",
      "emitkit.vault.cipher=local"
    })
@Testcontainers
class WRK04LintDiffVersionTest {

  /**
   * An undefined path parameter. The linter reports it as an ERROR (path-params-defined), and the
   * parser reports it again as a spec-parse ERROR. PARSE_SPEC would refuse this document, so the
   * step is driven here with a snapshot a real run never stores.
   */
  private static final String ERROR_SPEC =
      """
      openapi: 3.0.3
      info:
        title: Pets
        version: 1.0.0
      paths:
        /pets/{id}:
          get:
            operationId: getPet
            responses:
              '200':
                description: ok
      """;

  /** Warnings only: an operation with no description and no tags. */
  private static final String PETS_V1 =
      """
      openapi: 3.0.3
      info:
        title: Pets
        version: 1.0.0
      paths:
        /pets:
          get:
            operationId: listPets
            responses:
              '200':
                description: ok
      """;

  /** PETS_V1 plus one added operation. */
  private static final String PETS_V2 =
      """
      openapi: 3.0.3
      info:
        title: Pets
        version: 1.0.0
      paths:
        /pets:
          get:
            operationId: listPets
            responses:
              '200':
                description: ok
          post:
            operationId: createPet
            responses:
              '201':
                description: created
      """;

  private static final Instant T1 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-11-02T10:00:00Z");
  private static final Instant T3 = Instant.parse("2026-11-03T10:00:00Z");
  private static final AtomicLong SEQUENCE = new AtomicLong();
  private static final SpecParser PARSER = new SpecParser();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Autowired private UserRepository users;
  @Autowired private OrganizationRepository orgs;
  @Autowired private ProjectRepository projects;
  @Autowired private ProjectConfigRepository configs;
  @Autowired private GenerationRunRepository runs;
  @Autowired private SpecSnapshotRepository snapshots;
  @Autowired private LintResultRepository lintResults;
  @Autowired private SdkVersionRepository sdkVersions;
  @Autowired private PlatformTransactionManager transactions;

  private Project project;
  private ProjectConfig config;
  private LintSpecStep lint;
  private DiffSpecStep diff;
  private CalcVersionStep calc;

  @BeforeEach
  void seedProjectAndSteps() {
    long n = SEQUENCE.incrementAndGet();
    User creator =
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
    lint = new LintSpecStep(runs, snapshots, lintResults, transactions);
    diff = new DiffSpecStep(runs, snapshots, transactions);
    calc = new CalcVersionStep(runs, snapshots, sdkVersions, transactions);
  }

  @Test
  void lintFailOnError_withError_fails() {
    config.setLintFailOn(LintFailOn.ERROR);
    String runId = runWith(ERROR_SPEC, T3, RunStatus.RUNNING).getId();

    StepResult result = lint.execute(context(reload(runId)));

    assertThat(result)
        .isEqualTo(
            new StepResult.Fail(
                ErrorCode.LINT_FAILED, "2 lint errors: path-params-defined, spec-parse"));
    // The findings are kept even though the run fails on them.
    assertThat(lintResults.findByRunId(runId))
        .extracting(LintResult::getRuleId, LintResult::getSeverity)
        .contains(tuple("path-params-defined", Severity.ERROR));
  }

  @Test
  void lintFailOnNever_continues() {
    config.setLintFailOn(LintFailOn.NEVER);
    String runId = runWith(ERROR_SPEC, T3, RunStatus.RUNNING).getId();

    StepResult result = lint.execute(context(reload(runId)));

    assertThat(result).isEqualTo(new StepResult.Continue(null));
    assertThat(lintResults.findByRunId(runId))
        .extracting(LintResult::getRuleId)
        .contains("path-params-defined");
  }

  @Test
  void lintRowsReplacedOnRetry() {
    config.setLintFailOn(LintFailOn.NEVER);
    String runId = runWith(ERROR_SPEC, T3, RunStatus.RUNNING).getId();
    lint.execute(context(reload(runId)));
    int firstCount = lintResults.findByRunId(runId).size();

    lint.execute(context(reload(runId)));

    assertThat(firstCount).isPositive();
    assertThat(lintResults.findByRunId(runId)).hasSize(firstCount);
  }

  @Test
  void lintFailOnWarning_withWarning_fails() {
    config.setLintFailOn(LintFailOn.WARNING);
    String runId = runWith(PETS_V1, T3, RunStatus.RUNNING).getId();

    StepResult result = lint.execute(context(reload(runId)));

    assertThat(result)
        .isEqualTo(
            new StepResult.Fail(
                ErrorCode.LINT_FAILED, "2 lint errors: operation-description, operation-tags"));
  }

  @Test
  void lintFailOnError_withWarningOnly_continues() {
    config.setLintFailOn(LintFailOn.ERROR);
    String runId = runWith(PETS_V1, T3, RunStatus.RUNNING).getId();

    assertThat(lint.execute(context(reload(runId)))).isEqualTo(new StepResult.Continue(null));
  }

  @Test
  void diffBaselineIgnoresDryRuns() {
    String v1 = snapshot(PETS_V1);
    String v2 = snapshot(PETS_V2);
    run(T1, false, RunStatus.SUCCESS, v1);
    // A successful dry run of the same spec as the current run must not be the baseline.
    run(T2, true, RunStatus.SUCCESS, v2);
    String runId = run(T3, false, RunStatus.RUNNING, v2).getId();

    StepResult result = diff.execute(context(reload(runId)));

    assertThat(result).isEqualTo(new StepResult.Continue(new DiffSpecStep.Output(false)));
    assertThat(diffOf(runId).added()).extracting(OpRef::operationId).containsExactly("createPet");
  }

  @Test
  void firstRun_version010() {
    String runId = runWith(PETS_V1, T3, RunStatus.RUNNING).getId();

    assertThat(diff.execute(context(reload(runId))))
        .isEqualTo(new StepResult.Continue(new DiffSpecStep.Output(true)));
    assertThat(diffOf(runId).added()).extracting(OpRef::operationId).containsExactly("listPets");

    assertThat(calc.execute(context(reload(runId))))
        .isEqualTo(new StepResult.Continue(new CalcVersionStep.Output("0.1.0", true)));
    GenerationRun run = reload(runId);
    assertThat(run.getSdkVersion()).isEqualTo("0.1.0");
    assertThat(run.getChangeType()).isEqualTo(ChangeType.MINOR);
  }

  @Test
  void noChange_versionUnchanged_changedFalse() {
    GenerationRun previous = runWith(PETS_V1, T1, RunStatus.SUCCESS);
    sdkVersions.saveAndFlush(
        new SdkVersion(
            null, project.getId(), "0.2.0", previous.getId(), ChangeType.MINOR, "", null));
    String runId = runWith(PETS_V1, T3, RunStatus.RUNNING).getId();
    diff.execute(context(reload(runId)));

    assertThat(calc.execute(context(reload(runId))))
        .isEqualTo(new StepResult.Continue(new CalcVersionStep.Output("0.2.0", false)));
    GenerationRun run = reload(runId);
    assertThat(run.getSdkVersion()).isEqualTo("0.2.0");
    assertThat(run.getChangeType()).isNull();
  }

  @Test
  void addedOperation_bumpsPatchBelowOne() {
    GenerationRun previous = runWith(PETS_V1, T1, RunStatus.SUCCESS);
    sdkVersions.saveAndFlush(
        new SdkVersion(
            null, project.getId(), "0.2.0", previous.getId(), ChangeType.MINOR, "", null));
    String runId = runWith(PETS_V2, T3, RunStatus.RUNNING).getId();
    diff.execute(context(reload(runId)));

    assertThat(calc.execute(context(reload(runId))))
        .isEqualTo(new StepResult.Continue(new CalcVersionStep.Output("0.2.1", true)));
    assertThat(reload(runId).getChangeType()).isEqualTo(ChangeType.PATCH);
  }

  @Test
  void lintSpec_terminalRun_parksWithoutWriting() {
    config.setLintFailOn(LintFailOn.ERROR);
    String runId = runWith(ERROR_SPEC, T3, RunStatus.SUCCESS).getId();

    assertThat(lint.execute(context(reload(runId)))).isEqualTo(new StepResult.Park());
    assertThat(lintResults.findByRunId(runId)).isEmpty();
  }

  @Test
  void diffSpec_terminalRun_parksWithoutWriting() {
    String runId = runWith(PETS_V1, T3, RunStatus.FAILED).getId();

    assertThat(diff.execute(context(reload(runId)))).isEqualTo(new StepResult.Park());
    assertThat(reload(runId).getDiffSummary()).isNull();
  }

  @Test
  void calcVersion_terminalRun_parksWithoutWriting() {
    String runId = runWith(PETS_V1, T3, RunStatus.RUNNING).getId();
    diff.execute(context(reload(runId)));
    GenerationRun finished = reload(runId);
    finished.setStatus(RunStatus.CANCELLED);
    runs.saveAndFlush(finished);

    assertThat(calc.execute(context(reload(runId)))).isEqualTo(new StepResult.Park());
    assertThat(reload(runId).getSdkVersion()).isNull();
    assertThat(reload(runId).getChangeType()).isNull();
  }

  /**
   * The project's snapshot of the spec, stored as PARSE_SPEC stores it: one per content hash, so
   * runs of the same content share it.
   */
  private String snapshot(String spec) {
    ParseResult parsed = PARSER.parse(spec);
    return snapshots
        .findByProjectIdAndContentHash(project.getId(), parsed.contentHash())
        .orElseGet(() ->
            snapshots.saveAndFlush(
                new SpecSnapshot(
                    null,
                    project.getId(),
                    parsed.contentHash(),
                    spec,
                    ApiModelJson.toJson(parsed.model()),
                    parsed.infoVersion())))
        .getId();
  }

  /** A run of the project pointing at a fresh snapshot of {@code spec}. */
  private GenerationRun runWith(String spec, Instant createdAt, RunStatus status) {
    return run(createdAt, false, status, snapshot(spec));
  }

  private GenerationRun run(
      Instant createdAt, boolean dryRun, RunStatus status, String snapshotId) {
    GenerationRun run =
        new GenerationRun(
            null, project.getId(), config.getId(), RunTrigger.MANUAL, dryRun, createdAt);
    run.setStatus(status);
    run.setSpecSnapshotId(snapshotId);
    return runs.saveAndFlush(run);
  }

  private RunContext context(GenerationRun run) {
    return new RunContext(
        run,
        project,
        config,
        () -> {
          throw new UnsupportedOperationException("no VCS in these steps");
        },
        RunLogger.NOOP,
        new CancellationRegistry(runs));
  }

  private GenerationRun reload(String runId) {
    return runs.findById(runId).orElseThrow();
  }

  private SpecDiff diffOf(String runId) {
    return JSON.readValue(reload(runId).getDiffSummary(), SpecDiff.class);
  }

  /** Boots JPA and Flyway against the container, with the domain's entities and repositories. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @EntityScan("dev.emitkit.domain")
  @EnableJpaRepositories("dev.emitkit.domain")
  static class TestApp {}
}
