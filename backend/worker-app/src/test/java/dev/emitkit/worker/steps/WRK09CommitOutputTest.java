package dev.emitkit.worker.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.id.Ids;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.SeverityCount;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.spec.diff.Change;
import dev.emitkit.spec.diff.ChangeKind;
import dev.emitkit.spec.diff.OpChange;
import dev.emitkit.spec.diff.OpRef;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.fake.InMemoryVcsClient;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * COMMIT_OUTPUT against an in-memory VCS, a work dir on disk and a fake run repository. No
 * database, no Docker.
 */
class WRK09CommitOutputTest {

  private static final String USER = "user-1";
  private static final String REPO = "acme/api";
  private static final String OUT_REPO = "acme/out";
  private static final String WEB = "http://web.test";
  private static final String CONFIG_ID = Ids.newUlid();
  private static final String RUN_ID = Ids.newUlid();
  private static final String BRANCH = "emitkit/run-" + RUN_ID;
  private static final String RUN_URL = WEB + "/runs/" + RUN_ID;

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

  private InMemoryVcsClient vcs;
  private WorkDir workDir;
  private FakeRuns runs;
  private List<SeverityCount> lintCounts;
  private CancellationRegistry cancellation;
  private CommitOutputStep step;
  private Project project;
  private GenerationRun run;

  @BeforeEach
  void setUp() {
    vcs = new InMemoryVcsClient();
    vcs.createRepo("acme", "api", false);
    vcs.createRepo("acme", "out", false);
    workDir = new WorkDir(root);
    runs = new FakeRuns();
    lintCounts = List.of();
    cancellation = new CancellationRegistry(runs.repo());
    step = new CommitOutputStep(workDir, runs.repo(), lintRepo(), NO_TRANSACTIONS, WEB + "/");
    project = project(OutputMode.APPEND);
  }

  @Test
  void dryRun_skipsCommitAndPr() {
    givenRun(true, null);
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    StepResult result = step.execute(context());

    assertThat(output(result)).isEqualTo(new CommitOutputStep.Skipped(true));
    assertThat(vcs.branches()).containsExactly("main");
    assertThat(vcs.prs()).isEmpty();
    assertThat(run.getBranchName()).isNull();
  }

  @Test
  void append_commitsToSameRepo() {
    givenRun(false, null);
    lintCounts = List.of(new SeverityCount(Severity.WARNING, 2));
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    var output = (CommitOutputStep.Output) output(step.execute(context()));

    assertThat(output.branchName()).isEqualTo(BRANCH);
    assertThat(output.prNumber()).isEqualTo(1);
    assertThat(vcs.branches(REPO)).contains(BRANCH);
    assertThat(vcs.branches(OUT_REPO)).containsExactly("main");
    assertThat(vcs.getFile(REPO, "sdk/index.ts", BRANCH).content()).isEqualTo("index");
    assertThat(vcs.prs())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.repo()).isEqualTo(REPO);
          assertThat(pr.base()).isEqualTo("main");
          assertThat(pr.body()).contains("Errors: 0", "Warnings: 2", "Info: 0", RUN_URL);
        });
    // The branch and the pull request are saved on the run as they are created.
    assertThat(run.getBranchName()).isEqualTo(BRANCH);
    assertThat(run.getPrNumber()).isEqualTo(1);
    assertThat(run.getPrUrl()).isEqualTo(output.prUrl());
  }

  @Test
  void separate_commitsToOutputRepo() {
    project = project(OutputMode.SEPARATE);
    givenRun(false, null);
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    step.execute(context());

    assertThat(vcs.branches(OUT_REPO)).contains(BRANCH);
    assertThat(vcs.branches(REPO)).containsExactly("main");
    assertThat(vcs.prs())
        .singleElement()
        .satisfies(pr -> assertThat(pr.repo()).isEqualTo(OUT_REPO));
  }

  @Test
  void branchAndTitle() {
    givenRun(false, null);
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    step.execute(context());

    assertThat(vcs.prs())
        .singleElement()
        .satisfies(pr -> {
          assertThat(pr.head()).isEqualTo("emitkit/run-" + RUN_ID);
          assertThat(pr.title()).isEqualTo("EmitKit: SDK v0.2.0");
        });
  }

  @Test
  void skippedAndUnchangedNotCommitted() {
    seed(REPO, "sdk/same.ts", "same");
    seed(REPO, "sdk/custom/Stub.ts", "user edit");
    givenRun(false, null);
    // Only a file the target already has, and a stub it owns: nothing to commit, no pull request.
    generate(
        new GeneratedFile("sdk/same.ts", "same", FileKind.GENERATED),
        new GeneratedFile("sdk/custom/Stub.ts", "stub", FileKind.CUSTOM_STUB));

    assertThat(output(step.execute(context()))).isEqualTo(new CommitOutputStep.NoChanges(true));
    assertThat(vcs.branches(REPO)).containsExactly("main");
    assertThat(vcs.prs()).isEmpty();

    // With a new file added, the branch holds that file and leaves the user's stub alone.
    generate(
        new GeneratedFile("sdk/same.ts", "same", FileKind.GENERATED),
        new GeneratedFile("sdk/custom/Stub.ts", "stub", FileKind.CUSTOM_STUB),
        new GeneratedFile("sdk/custom/Fresh.ts", "fresh", FileKind.CUSTOM_STUB));

    step.execute(context());

    assertThat(vcs.getFile(REPO, "sdk/custom/Fresh.ts", BRANCH).content()).isEqualTo("fresh");
    assertThat(vcs.getFile(REPO, "sdk/custom/Stub.ts", BRANCH).content()).isEqualTo("user edit");
  }

  @Test
  void breaking_postsOneComment() {
    givenRun(false, breakingDiff());
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));

    step.execute(context());

    assertThat(vcs.comments(REPO, 1)).hasSize(1);
    assertThat(vcs.comments(REPO, 1).get(0))
        .contains("Breaking: `GET /pets (listPets)` removed", "query.limit", RUN_URL);

    // A redelivered run reads the saved output and does not post the comment again.
    step.execute(context());

    assertThat(vcs.comments(REPO, 1)).hasSize(1);
    assertThat(vcs.prs()).singleElement().satisfies(pr -> assertThat(pr.number()).isEqualTo(1));
  }

  @Test
  void conflict_failsVcsConflict() {
    givenRun(false, null);
    generate(new GeneratedFile("sdk/index.ts", "index", FileKind.GENERATED));
    vcs.failNext("commitTree", new VcsConflictException("stale sha for sdk/index.ts"));

    assertThatThrownBy(() -> step.execute(context()))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.VCS_CONFLICT));
    assertThat(run.getPrNumber()).isNull();
  }

  private Project project(OutputMode mode) {
    Project created = new Project(null, "org-1", REPO, "openapi.yaml", "main", mode, USER);
    if (mode == OutputMode.SEPARATE) {
      created.setOutputRepoFullName(OUT_REPO);
    }
    return created;
  }

  /** Creates the run for the test, in the fake repository, with the version the snapshot holds. */
  private void givenRun(boolean dryRun, SpecDiff diff) {
    run = new GenerationRun(RUN_ID, project.getId(), CONFIG_ID, RunTrigger.MANUAL, dryRun, null);
    ReflectionTestUtils.setField(run, "sdkVersion", "0.2.0");
    if (diff != null) {
      run.setDiffSummary(json(diff));
    }
    runs.rows.put(RUN_ID, run);
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

  private void seed(String repo, String path, String content) {
    vcs.commitTree(repo, "main", List.of(new CommitFile(path, content)), "seed");
  }

  private void generate(GeneratedFile... files) {
    workDir.write(RUN_ID, "gen-1", List.of(files));
  }

  private RunContext context() {
    ProjectConfig config =
        new ProjectConfig(CONFIG_ID, project.getId(), 1, VersionStrategy.EMITKIT_MANAGED, USER);
    return new RunContext(run, project, config, () -> vcs, RunLogger.NOOP, cancellation);
  }

  private static Object output(StepResult result) {
    return ((StepResult.Continue) result).output();
  }

  private LintResultRepository lintRepo() {
    return (LintResultRepository)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {LintResultRepository.class},
            (proxy, method, args) -> {
              if (method.getName().equals("countBySeverity")) {
                return lintCounts;
              }
              throw new UnsupportedOperationException(method.getName());
            });
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
}
