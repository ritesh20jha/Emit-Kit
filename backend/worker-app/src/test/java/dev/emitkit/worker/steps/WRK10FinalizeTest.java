package dev.emitkit.worker.steps;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.common.id.Ids;
import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.spec.diff.Change;
import dev.emitkit.spec.diff.ChangeKind;
import dev.emitkit.spec.diff.OpChange;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepOutputs;
import dev.emitkit.worker.pipeline.StepResult;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * FINALIZE against a work dir on disk and fake run and version repositories. No database, no
 * Docker.
 */
class WRK10FinalizeTest {

  private static final String USER = "user-1";
  private static final String REPO = "acme/api";
  private static final String CONFIG_ID = Ids.newUlid();
  private static final String RUN_ID = Ids.newUlid();
  private static final String LAST_VERSION = "0.2.0";
  private static final String NEW_VERSION = "0.3.0";
  private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");
  private static final String CHANGELOG =
      "- Breaking: `GET /pets (listPets)` removed\n"
          + "- Breaking: `POST /pets (createPet)` param removed at `query.limit`";

  /** Transactions here have nothing to commit or roll back. */
  private static final PlatformTransactionManager NO_TRANSACTIONS =
      new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
          return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
      };

  @TempDir Path root;

  private WorkDir workDir;
  private FakeRuns runs;
  private FakeVersions versions;
  private CountingLogger log;
  private CancellationRegistry cancellation;
  private FinalizeStep step;
  private Project project;
  private GenerationRun run;

  @BeforeEach
  void setUp() {
    workDir = new WorkDir(root);
    runs = new FakeRuns();
    versions = new FakeVersions();
    log = new CountingLogger();
    cancellation = new CancellationRegistry(runs.repo());
    step =
        new FinalizeStep(
            workDir, runs.repo(), versions.repo(), NO_TRANSACTIONS, InstantSource.fixed(NOW));
    project = new Project(null, "org-1", REPO, "openapi.yaml", "main", OutputMode.APPEND, USER);
  }

  @Test
  void changedVersion_insertsSdkVersion() {
    givenRun(ChangeType.MAJOR, false, breakingDiff());

    StepResult result = step.execute(context());

    assertThat(output(result)).isEqualTo(new FinalizeStep.Output(NEW_VERSION, true));
    assertThat(versions.rows)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getProjectId()).isEqualTo(project.getId());
          assertThat(row.getVersion()).isEqualTo(NEW_VERSION);
          assertThat(row.getRunId()).isEqualTo(RUN_ID);
          assertThat(row.getChangeType()).isEqualTo(ChangeType.MAJOR);
          assertThat(row.getChangelog()).isEqualTo(CHANGELOG);
        });
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
    assertThat(run.getFinishedAt()).isEqualTo(NOW);
  }

  @Test
  void unchanged_noRow() {
    givenRun(null, false, breakingDiff());

    StepResult result = step.execute(context());

    assertThat(output(result)).isEqualTo(new FinalizeStep.Output(LAST_VERSION, false));
    assertThat(versions.rows).isEmpty();
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void dryRun_noRow() {
    givenRun(ChangeType.MAJOR, true, breakingDiff());

    StepResult result = step.execute(context());

    assertThat(output(result)).isEqualTo(new FinalizeStep.Output(NEW_VERSION, false));
    assertThat(versions.rows).isEmpty();
    assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
  }

  @Test
  void doneCalled_workDirDeleted() {
    givenRun(ChangeType.MINOR, false, null);

    step.execute(context());

    assertThat(log.doneCalls).isEqualTo(1);
    assertThat(Files.exists(workDir.of(RUN_ID))).isFalse();
  }

  @Test
  void rerunAfterFinalize_noDuplicateRow() {
    givenRun(ChangeType.MINOR, false, null);
    step.execute(context());

    // A redelivered run finds its row terminal: it writes nothing and parks.
    assertThat(step.execute(context())).isInstanceOf(StepResult.Park.class);

    // Another run that releases the same version finds the version already recorded.
    givenAnotherRun(ChangeType.MINOR, false, null);
    step.execute(context());

    assertThat(versions.rows)
        .singleElement()
        .satisfies(row -> assertThat(row.getRunId()).isEqualTo(RUN_ID));
    assertThat(log.doneCalls).isEqualTo(2);
  }

  /** A run in RUNNING with the version CALC_VERSION saved for it. */
  private void givenRun(ChangeType changeType, boolean dryRun, SpecDiff diff) {
    run = newRun(RUN_ID, changeType, dryRun, diff);
    runs.rows.put(RUN_ID, run);
    workDir.write(
        RUN_ID, "gen-1", List.of(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED)));
  }

  private void givenAnotherRun(ChangeType changeType, boolean dryRun, SpecDiff diff) {
    run = newRun(Ids.newUlid(), changeType, dryRun, diff);
    runs.rows.put(run.getId(), run);
  }

  private GenerationRun newRun(String id, ChangeType changeType, boolean dryRun, SpecDiff diff) {
    GenerationRun created =
        new GenerationRun(id, project.getId(), CONFIG_ID, RunTrigger.MANUAL, dryRun, null);
    created.setStatus(RunStatus.RUNNING);
    String version = changeType == null ? LAST_VERSION : NEW_VERSION;
    created.setSdkVersion(version);
    created.setChangeType(changeType);
    created.setStepOutputs(
        StepOutputs.with(
            "{}", StepName.CALC_VERSION, new CalcVersionStep.Output(version, changeType != null)));
    if (diff != null) {
      created.setDiffSummary(json(diff));
    }
    return created;
  }

  private static SpecDiff breakingDiff() {
    var removed = new OpRef("listPets", "GET", "/pets");
    var changed = new OpRef("createPet", "POST", "/pets");
    return new SpecDiff(
        List.of(),
        List.of(removed),
        List.of(
            new OpChange(
                changed, List.of(new Change(ChangeKind.PARAM_REMOVED, "query.limit", true)))));
  }

  private static String json(SpecDiff diff) {
    return JsonMapper.builder().build().writeValueAsString(diff);
  }

  private RunContext context() {
    ProjectConfig config =
        new ProjectConfig(CONFIG_ID, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, USER);
    Supplier<VcsClient> noVcs =
        () -> {
          throw new IllegalStateException("FINALIZE uses no VCS");
        };
    return new RunContext(run, project, config, noVcs, log, cancellation);
  }

  private static Object output(StepResult result) {
    return ((StepResult.Continue) result).output();
  }

  /** Counts the calls to {@code done()}. The other lines are not kept. */
  private static final class CountingLogger implements RunLogger {

    int doneCalls;

    @Override
    public void info(String message) {}

    @Override
    public void warn(String message) {}

    @Override
    public void error(String message) {}

    @Override
    public void mask(String secret) {}

    @Override
    public void step(StepName step) {}

    @Override
    public void flush() {}

    @Override
    public void done() {
      doneCalls++;
    }
  }

  /** The run rows, served through the calls the step and the cancellation check make. */
  private static final class FakeRuns {

    final Map<String, GenerationRun> rows = new HashMap<>();

    GenerationRunRepository repo() {
      return (GenerationRunRepository)
          Proxy.newProxyInstance(
              FakeRuns.class.getClassLoader(),
              new Class<?>[] {GenerationRunRepository.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "findById", "findByIdForUpdate" ->
                        Optional.ofNullable(rows.get((String) args[0]));
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }

  /** The released versions, served through the calls the step makes. */
  private static final class FakeVersions {

    final List<SdkVersion> rows = new ArrayList<>();

    SdkVersionRepository repo() {
      return (SdkVersionRepository)
          Proxy.newProxyInstance(
              FakeVersions.class.getClassLoader(),
              new Class<?>[] {SdkVersionRepository.class},
              (proxy, method, args) ->
                  switch (method.getName()) {
                    case "save" -> {
                      rows.add((SdkVersion) args[0]);
                      yield args[0];
                    }
                    case "existsByProjectIdAndVersion" ->
                        rows.stream()
                            .anyMatch(row ->
                                row.getProjectId().equals(args[0])
                                    && row.getVersion().equals(args[1]));
                    default -> throw new UnsupportedOperationException(method.getName());
                  });
    }
  }
}
