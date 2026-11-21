package dev.emitkit.worker.steps;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.Decision;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.RunApproval;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Applies the project's breaking-change policy to the diff DIFF_SPEC stored (spec v1 §5.1, step 6).
 * With no breaking change, or policy ALLOW, the run continues. BLOCK fails it. REQUIRE_APPROVAL
 * parks it awaiting a reviewer; once the reviewer approves, the run resumes here and continues.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class BreakingGateStep implements PipelineStep {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What BREAKING_GATE saves: how many breaking changes the diff has. */
  public record Output(int breakingChanges) {}

  /** Where a run that needs approval stands, as read under the run's lock. */
  private enum Approval {
    APPROVED,
    REJECTED,
    PENDING,
    RUN_FINISHED
  }

  private final GenerationRunRepository runs;
  private final RunApprovalRepository approvals;
  private final TransactionTemplate transactions;

  @Autowired
  public BreakingGateStep(
      GenerationRunRepository runs,
      RunApprovalRepository approvals,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.approvals = approvals;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return StepName.BREAKING_GATE;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    SpecDiff diff =
        JSON.readValue(
            Objects.requireNonNull(run.getDiffSummary(), "DIFF_SPEC has saved no diff"),
            SpecDiff.class);
    List<PrBody.Line> breaking = PrBody.lines(diff).stream().filter(PrBody.Line::breaking).toList();
    int count = breaking.size();
    BreakingGate policy = ctx.config().getBreakingGate();
    if (count == 0 || policy == BreakingGate.ALLOW) {
      return new StepResult.Continue(new Output(count));
    }
    if (policy == BreakingGate.BLOCK) {
      return new StepResult.Fail(
          ErrorCode.BREAKING_BLOCKED, count + " breaking changes; policy is BLOCK");
    }
    return switch (requestApproval(run.getId())) {
      case APPROVED -> new StepResult.Continue(new Output(count));
      case REJECTED ->
          new StepResult.Fail(
              ErrorCode.BREAKING_BLOCKED, "breaking changes were rejected at approval");
      case PENDING -> {
        ctx.log().warn(count + " breaking changes; waiting for approval");
        breaking.forEach(line -> ctx.log().info(line.text()));
        // The lines are saved before the run stops, since the runner does not flush them on Park.
        ctx.log().flush();
        yield new StepResult.Park();
      }
      case RUN_FINISHED -> new StepResult.Park();
    };
  }

  /**
   * In one transaction: reads the run's approval under the run's lock. Creates the pending approval
   * if there is none, and marks the run AWAITING_APPROVAL. Writes nothing if the run has become
   * terminal, or if a decision is already recorded. Throws {@link CancelledException}, and writes
   * nothing, if the run has been asked to stop.
   */
  private Approval requestApproval(String runId) {
    Approval outcome =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .map(run -> {
                  if (run.getStatus().isTerminal()) {
                    return Approval.RUN_FINISHED;
                  }
                  if (run.isCancelRequested()) {
                    throw new CancelledException(runId);
                  }
                  Optional<RunApproval> approval = approvals.findByRunId(runId);
                  Decision decision = approval.map(RunApproval::getDecision).orElse(null);
                  if (decision == Decision.APPROVED) {
                    return Approval.APPROVED;
                  }
                  if (decision == Decision.REJECTED) {
                    return Approval.REJECTED;
                  }
                  if (approval.isEmpty()) {
                    approvals.save(new RunApproval(null, runId, null));
                  }
                  run.setStatus(RunStatus.AWAITING_APPROVAL);
                  return Approval.PENDING;
                })
                .orElse(Approval.RUN_FINISHED));
    return Objects.requireNonNull(outcome);
  }
}
