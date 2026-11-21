package dev.emitkit.worker.messaging;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.worker.logs.RunLoggerFactory;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes a cancelled run's branch and marks it CANCELLED. A finished run is left alone, so the
 * branch of a finished run with a pull request is kept.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
class CancelCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(CancelCleanup.class);

  private final GenerationRunRepository runs;
  private final ProjectRepository projects;
  private final VcsProvider vcsProvider;
  private final TransactionTemplate transactions;
  private final RunLoggerFactory loggers;

  CancelCleanup(
      GenerationRunRepository runs,
      ProjectRepository projects,
      VcsProvider vcsProvider,
      PlatformTransactionManager transactionManager,
      RunLoggerFactory loggers) {
    this.runs = runs;
    this.projects = projects;
    this.vcsProvider = vcsProvider;
    this.transactions = new TransactionTemplate(transactionManager);
    this.loggers = loggers;
  }

  /** Ends the cancelled run {@code runId}. The caller holds the run's lock. */
  void cancelled(String runId) {
    GenerationRun run = runs.findById(runId).orElse(null);
    if (run == null || run.getStatus().isTerminal()) {
      return;
    }
    if (run.getBranchName() != null) {
      deleteBranch(run.getProjectId(), run.getBranchName(), runId);
    }
    boolean ended =
        RunUpdates.ifRunning(
            transactions,
            runs,
            runId,
            r -> {
              r.setStatus(RunStatus.CANCELLED);
              r.setFinishedAt(Instant.now());
            });
    if (ended) {
      loggers.create(runId).done();
    }
  }

  /** Deletes the branch in the repo the run committed to. A failure is logged, not thrown. */
  private void deleteBranch(String projectId, String branchName, String runId) {
    try {
      Project project =
          projects.findById(projectId).orElseThrow(() -> new IllegalStateException(projectId));
      String repo =
          project.getOutputMode() == OutputMode.SEPARATE
              ? project.getOutputRepoFullName()
              : project.getRepoFullName();
      vcsProvider.forProject(project.getCreatedBy()).deleteBranch(repo, branchName);
    } catch (RuntimeException e) {
      LOG.warn("could not delete branch {} of cancelled run {}", branchName, runId, e);
    }
  }
}
