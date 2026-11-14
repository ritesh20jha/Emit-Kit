package dev.emitkit.api.runs;

import dev.emitkit.api.runs.StepState.StepStatus;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunArtifactRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.SeverityCount;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.StepSpan;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/** Builds the API views of a run from its row and the rows that hang off it. */
@Component
class RunMapper {

  private final GenerationRunLogRepository logs;
  private final LintResultRepository lintResults;
  private final RunApprovalRepository approvals;
  private final RunArtifactRepository artifacts;
  private final UserRepository users;

  RunMapper(
      GenerationRunLogRepository logs,
      LintResultRepository lintResults,
      RunApprovalRepository approvals,
      RunArtifactRepository artifacts,
      UserRepository users) {
    this.logs = logs;
    this.lintResults = lintResults;
    this.approvals = approvals;
    this.artifacts = artifacts;
    this.users = users;
  }

  RunSummary toSummary(GenerationRun run) {
    return new RunSummary(
        run.getId(),
        run.getStatus(),
        run.getTrigger(),
        run.isDryRun(),
        run.getSdkVersion(),
        elapsedMs(run.getStartedAt(), run.getFinishedAt()),
        run.getPrNumber(),
        run.getPrUrl(),
        run.getCreatedAt());
  }

  RunDetail toDetail(GenerationRun run) {
    String runId = run.getId();
    return new RunDetail(
        runId,
        run.getProjectId(),
        run.getStatus(),
        run.getTrigger(),
        run.isDryRun(),
        run.getCurrentStep(),
        steps(run, spansByStep(runId)),
        run.getFailureCode(),
        run.getFailureMessage(),
        run.getCommitSha(),
        run.getSdkVersion(),
        run.getChangeType(),
        // The diff is written by the worker's DIFF_SPEC step, which does not exist yet, so a run
        // has no summary to read here.
        null,
        lintCounts(runId),
        approval(runId),
        run.getBranchName(),
        run.getPrUrl(),
        run.getParentRunId(),
        run.getCreatedAt(),
        run.getStartedAt(),
        run.getFinishedAt(),
        // A run's artifact is downloadable while its row exists. The download endpoint (RUN-04)
        // reads the same row.
        artifacts.findByRunId(runId).isPresent());
  }

  private Map<StepName, StepSpan> spansByStep(String runId) {
    return logs.findStepSpans(runId).stream()
        .collect(Collectors.toMap(StepSpan::step, Function.identity()));
  }

  /**
   * One state per pipeline step, in pipeline order. Steps before the current one are DONE when they
   * wrote log lines, otherwise SKIPPED. The current step is RUNNING, or FAILED when the run failed
   * or was cancelled. A successful run has every step DONE.
   */
  private static List<StepState> steps(GenerationRun run, Map<StepName, StepSpan> spans) {
    StepName current = run.getCurrentStep();
    int currentIndex = current == null ? -1 : current.ordinal();
    return Arrays.stream(StepName.values())
        .map(step -> {
          StepSpan span = spans.get(step);
          Long durationMs = span == null ? null : elapsedMs(span.first(), span.last());
          return new StepState(
              step, stepStatus(run.getStatus(), step, currentIndex, span != null), durationMs);
        })
        .toList();
  }

  private static StepStatus stepStatus(
      RunStatus runStatus, StepName step, int currentIndex, boolean hasLogs) {
    if (runStatus == RunStatus.SUCCESS) {
      return StepStatus.DONE;
    }
    int index = step.ordinal();
    if (index < currentIndex) {
      return hasLogs ? StepStatus.DONE : StepStatus.SKIPPED;
    }
    if (index == currentIndex) {
      return runStatus == RunStatus.FAILED || runStatus == RunStatus.CANCELLED
          ? StepStatus.FAILED
          : StepStatus.RUNNING;
    }
    return StepStatus.PENDING;
  }

  private LintCounts lintCounts(String runId) {
    int errors = 0;
    int warnings = 0;
    int infos = 0;
    for (SeverityCount count : lintResults.countBySeverity(runId)) {
      switch (count.severity()) {
        case ERROR -> errors = Math.toIntExact(count.count());
        case WARNING -> warnings = Math.toIntExact(count.count());
        case INFO -> infos = Math.toIntExact(count.count());
      }
    }
    return new LintCounts(errors, warnings, infos);
  }

  /** The approval view, or null when the run has no approval gate. */
  private @Nullable ApprovalView approval(String runId) {
    return approvals
        .findByRunId(runId)
        .map(approval ->
            new ApprovalView(
                approval.getDecision(),
                loginOf(approval.getDecidedBy()),
                approval.getComment(),
                approval.getDecidedAt()))
        .orElse(null);
  }

  private @Nullable String loginOf(@Nullable String userId) {
    if (userId == null) {
      return null;
    }
    return users.findById(userId).map(User::getLogin).orElse(null);
  }

  /** Milliseconds from {@code from} to {@code to}, or null while either end is still unknown. */
  private static @Nullable Long elapsedMs(@Nullable Instant from, @Nullable Instant to) {
    return from == null || to == null ? null : Duration.between(from, to).toMillis();
  }
}
