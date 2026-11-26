package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.COMMIT_OUTPUT;

import dev.emitkit.domain.FileStatus;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.SeverityCount;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.vcs.CommitFile;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.PullRequest;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepOutputs;
import dev.emitkit.worker.pipeline.StepResult;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Commits the run's changed files to a new branch, opens the pull request and, for a breaking
 * change, comments on it (spec v1 §5.1 step 10). A dry run commits nothing.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class CommitOutputStep implements PipelineStep {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final WorkDir workDir;
  private final GenerationRunRepository runs;
  private final LintResultRepository lintResults;
  private final TransactionTemplate transactions;
  private final String webBaseUrl;

  @Autowired
  public CommitOutputStep(
      WorkDir workDir,
      GenerationRunRepository runs,
      LintResultRepository lintResults,
      PlatformTransactionManager transactionManager,
      @Value("${emitkit.web.base-url:http://localhost:5173}") String webBaseUrl) {
    this.workDir = workDir;
    this.runs = runs;
    this.lintResults = lintResults;
    this.transactions = new TransactionTemplate(transactionManager);
    this.webBaseUrl = webBaseUrl.replaceAll("/+$", "");
  }

  @Override
  public StepName name() {
    return COMMIT_OUTPUT;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    if (run.isDryRun()) {
      ctx.log().info("Dry run: nothing committed");
      return new StepResult.Continue(new Skipped(true));
    }
    String runId = run.getId();
    String runUrl = webBaseUrl + "/runs/" + runId;
    VcsClient vcs = ctx.vcs();
    PackageArtifactStep.Target target = PackageArtifactStep.target(ctx.project(), ctx);

    List<WorkDir.Entry> files = workDir.manifest(runId);
    Map<String, String> contents = PackageArtifactStep.readContents(workDir, runId, files);
    ctx.checkCancelled();
    Map<String, String> targetBlobs = vcs.listBlobShas(target.repo(), target.branch(), "");
    List<CommitFile> changed = changedFiles(ManifestBuilder.plan(files, contents, targetBlobs));
    if (changed.isEmpty()) {
      ctx.log().info("No files changed: no pull request");
      return new StepResult.Continue(new NoChanges(true));
    }
    ctx.checkCancelled();

    String branch = "emitkit/run-" + runId;
    String title = "EmitKit: SDK v" + run.getSdkVersion();
    // The branch is cut once: after the commit it no longer points at the base, so createBranch
    // would conflict on a retry.
    if (run.getBranchName() == null) {
      vcs.createBranch(target.repo(), branch, vcs.branchHead(target.repo(), target.branch()));
      if (!saveRun(runId, r -> r.setBranchName(branch))) {
        return new StepResult.Park();
      }
    }
    if (!holdsFiles(vcs, target.repo(), branch, changed)) {
      vcs.commitTree(target.repo(), branch, changed, title);
    }
    ctx.log().info("committed " + changed.size() + " files to " + branch);
    ctx.checkCancelled();

    SpecDiff diff = parseDiff(run.getDiffSummary());
    List<SeverityCount> lint = lintResults.countBySeverity(runId);
    PullRequest pr =
        vcs.openPr(
            target.repo(), branch, target.branch(), title, PrBody.render(diff, lint, runUrl));
    String prUrl = pr.htmlUrl();
    int prNumber = pr.number();
    if (!saveRun(
        runId,
        r -> {
          r.setPrUrl(prUrl);
          r.setPrNumber(prNumber);
        })) {
      return new StepResult.Park();
    }
    ctx.log().info("opened pull request " + prUrl);

    boolean commented =
        ctx.output(COMMIT_OUTPUT, Output.class).map(Output::commentPosted).orElse(false);
    if (diff.hasBreaking() && !commented) {
      vcs.comment(target.repo(), prNumber, BreakingComment.render(diff, runUrl));
      Output posted = new Output(branch, prUrl, prNumber, true);
      // Saved before the step returns: the runner skips a step that has a saved output, so a
      // redelivered run does not post the comment again.
      if (!saveRun(
          runId,
          r -> r.setStepOutputs(StepOutputs.with(r.getStepOutputs(), COMMIT_OUTPUT, posted)))) {
        return new StepResult.Park();
      }
      return new StepResult.Continue(posted);
    }
    return new StepResult.Continue(new Output(branch, prUrl, prNumber, commented));
  }

  /** The output of a dry run. Nothing was committed. */
  public record Skipped(boolean skipped) {}

  /** The output of a run with no changed files. No pull request was opened. */
  public record NoChanges(boolean noChanges) {}

  /**
   * The branch and pull request of the run. {@code commentPosted} is true once the breaking-change
   * comment is on the pull request.
   */
  public record Output(String branchName, String prUrl, int prNumber, boolean commentPosted) {}

  /** The files that differ from the target branch and are not user-owned stubs. */
  private static List<CommitFile> changedFiles(List<ManifestBuilder.Planned> planned) {
    return planned.stream()
        .filter(file -> file.entry().status() != FileStatus.SKIPPED)
        .filter(file -> file.entry().status() != FileStatus.UNCHANGED)
        .map(file -> new CommitFile(file.entry().path(), file.content()))
        .toList();
  }

  /**
   * Whether the branch already holds every changed file with its content, so that an earlier
   * attempt made the commit. The comparison is by blob sha, so it does not depend on the base.
   */
  private static boolean holdsFiles(
      VcsClient vcs, String repo, String branch, List<CommitFile> files) {
    Map<String, String> blobs = vcs.listBlobShas(repo, branch, "");
    return files.stream()
        .allMatch(file -> GitBlob.sha(file.content()).equals(blobs.get(file.path())));
  }

  /** The spec diff of the run, or an empty diff when none was recorded. */
  static SpecDiff parseDiff(@Nullable String json) {
    if (json == null || json.isBlank()) {
      return SpecDiff.empty();
    }
    return JSON.readValue(json, SpecDiff.class);
  }

  /**
   * Applies {@code change} to the run in its own transaction, on a row locked there. Returns false,
   * writing nothing, if the run has become terminal.
   */
  private boolean saveRun(String runId, Consumer<GenerationRun> change) {
    Boolean saved =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  change.accept(run);
                  return true;
                })
                .orElse(false));
    return Boolean.TRUE.equals(saved);
  }
}
