package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.FETCH_SPEC;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.StepName;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.error.VcsAuthException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads the spec at the head of the project's default branch and records that commit on the run
 * (spec v1 §5.1, step 1). A revoked token fails the run and is not retried.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class FetchSpecStep implements PipelineStep {

  /** The largest spec accepted, in UTF-8 bytes. The API applies the same limit on save. */
  static final int MAX_SPEC_BYTES = 2 * 1024 * 1024;

  static final String REVOKED =
      "The project creator's GitHub access no longer works. An admin must re-link the project.";

  private static final Pattern REPO_NAME = Pattern.compile("^[\\w.-]+/[\\w.-]+$");

  private final GenerationRunRepository runs;
  private final TransactionTemplate transactions;

  @Autowired
  public FetchSpecStep(
      GenerationRunRepository runs, PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return FETCH_SPEC;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    Project project = ctx.project();
    String repo = project.getRepoFullName();
    String branch = project.getDefaultBranch();
    String path = project.getSpecPath();
    if (!REPO_NAME.matcher(repo).matches()) {
      return new StepResult.Fail(ErrorCode.SPEC_NOT_FOUND, "invalid repository name: " + repo);
    }
    try {
      return fetch(ctx, repo, branch, path);
    } catch (VcsAuthException e) {
      return new StepResult.Fail(ErrorCode.VCS_AUTH, REVOKED);
    }
  }

  private StepResult fetch(RunContext ctx, String repo, String branch, String path) {
    VcsClient vcs = ctx.vcs();
    String commitSha;
    try {
      commitSha = vcs.branchHead(repo, branch);
    } catch (VcsNotFoundException e) {
      // GitHub answers 404 to a token that can no longer see a private repo, so this is not
      // necessarily a missing repo. The creator's access is the likely cause.
      return new StepResult.Fail(
          ErrorCode.SPEC_NOT_FOUND,
          "repository "
              + repo
              + " or branch "
              + branch
              + " was not found, or the project creator can no longer access it. "
              + "An admin must re-link the project.");
    }
    if (!recordCommit(ctx.run().getId(), commitSha)) {
      return new StepResult.Park();
    }
    FileContent file;
    try {
      file = vcs.getFile(repo, path, commitSha);
    } catch (VcsNotFoundException e) {
      return new StepResult.Fail(
          ErrorCode.SPEC_NOT_FOUND, "spec not found: " + path + " on " + branch);
    }
    if (file.content().getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
      return new StepResult.Fail(ErrorCode.SPEC_TOO_LARGE, "spec is larger than 2 MB");
    }
    return new StepResult.Continue(new FetchOutput(commitSha, file.content()));
  }

  /**
   * Saves the commit on the run in its own transaction. Returns false, and writes nothing, if the
   * run has become terminal.
   */
  private boolean recordCommit(String runId, String commitSha) {
    Boolean saved =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  run.setCommitSha(commitSha);
                  return true;
                })
                .orElse(false));
    return Boolean.TRUE.equals(saved);
  }
}
