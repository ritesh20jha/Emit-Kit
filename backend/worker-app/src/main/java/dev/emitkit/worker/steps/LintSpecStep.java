package dev.emitkit.worker.steps;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.LintFailOn;
import dev.emitkit.domain.LintResult;
import dev.emitkit.domain.LintResultRepository;
import dev.emitkit.domain.Severity;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.SpecLinter;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lints the run's spec and stores the findings (spec v1 §5.1, step 3). The run fails when a finding
 * reaches the project's lint_fail_on threshold.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class LintSpecStep implements PipelineStep {

  /** The most rule ids named in a failure message. */
  static final int MAX_REPORTED_RULES = 3;

  private static final SpecParser PARSER = new SpecParser();
  private static final SpecLinter LINTER = new SpecLinter();

  private final GenerationRunRepository runs;
  private final SpecSnapshotRepository snapshots;
  private final LintResultRepository lintResults;
  private final TransactionTemplate transactions;

  @Autowired
  public LintSpecStep(
      GenerationRunRepository runs,
      SpecSnapshotRepository snapshots,
      LintResultRepository lintResults,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.snapshots = snapshots;
    this.lintResults = lintResults;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return StepName.LINT_SPEC;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    String raw = snapshot(run).getRawContent();
    List<LintIssue> issues = LINTER.lint(PARSER.parse(raw), raw);
    if (!store(run.getId(), issues)) {
      return new StepResult.Park();
    }
    List<LintIssue> tripping =
        issues.stream()
            .filter(issue -> trips(ctx.config().getLintFailOn(), severityOf(issue)))
            .toList();
    if (tripping.isEmpty()) {
      return new StepResult.Continue(null);
    }
    String rules =
        tripping.stream()
            .limit(MAX_REPORTED_RULES)
            .map(LintIssue::ruleId)
            .collect(Collectors.joining(", "));
    return new StepResult.Fail(ErrorCode.LINT_FAILED, tripping.size() + " lint errors: " + rules);
  }

  /**
   * In one transaction: replaces the run's findings with {@code issues}. Returns false, and writes
   * nothing, if the run has become terminal.
   */
  private boolean store(String runId, List<LintIssue> issues) {
    Boolean stored =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  // A retry replaces the earlier findings, so none are counted twice.
                  lintResults.deleteByRunId(runId);
                  lintResults.saveAll(
                      issues.stream()
                          .map(issue ->
                              new LintResult(
                                  null,
                                  runId,
                                  issue.ruleId(),
                                  severityOf(issue),
                                  issue.jsonPath(),
                                  issue.message()))
                          .toList());
                  return true;
                })
                .orElse(false));
    return Boolean.TRUE.equals(stored);
  }

  /** Whether a finding of this severity reaches the threshold. */
  private static boolean trips(LintFailOn threshold, Severity severity) {
    return switch (threshold) {
      case NEVER -> false;
      case ERROR -> severity == Severity.ERROR;
      case WARNING -> severity != Severity.INFO;
    };
  }

  /** The spec-engine severity, mapped to the domain's by name. */
  private static Severity severityOf(LintIssue issue) {
    return Severity.valueOf(issue.severity().name());
  }

  private SpecSnapshot snapshot(GenerationRun run) {
    String id = Objects.requireNonNull(run.getSpecSnapshotId(), "run has no spec snapshot");
    return snapshots
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("spec snapshot not found: " + id));
  }
}
