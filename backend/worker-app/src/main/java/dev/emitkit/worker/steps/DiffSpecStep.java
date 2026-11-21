package dev.emitkit.worker.steps;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.spec.diff.SpecDiffer;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Diffs the run's spec against the spec of the last successful run that was not a dry run (spec v1
 * §5.1, step 4). The diff is kept on the run for CALC_VERSION.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class DiffSpecStep implements PipelineStep {

  private static final SpecDiffer DIFFER = new SpecDiffer();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What DIFF_SPEC saves: whether there was no earlier run to diff against. */
  public record Output(boolean firstRun) {}

  private final GenerationRunRepository runs;
  private final SpecSnapshotRepository snapshots;
  private final TransactionTemplate transactions;

  @Autowired
  public DiffSpecStep(
      GenerationRunRepository runs,
      SpecSnapshotRepository snapshots,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.snapshots = snapshots;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return StepName.DIFF_SPEC;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    ApiModel current = ApiModelJson.fromJson(snapshot(run).getApiModel());
    Optional<ApiModel> baseline =
        runs.findLastSuccessfulNonDryRun(run.getProjectId())
            .filter(previous -> !previous.getId().equals(run.getId()))
            .map(GenerationRun::getSpecSnapshotId)
            .flatMap(snapshots::findById)
            .map(previous -> ApiModelJson.fromJson(previous.getApiModel()));
    SpecDiff diff = DIFFER.diff(baseline.orElse(null), current);
    if (!save(run.getId(), JSON.writeValueAsString(diff))) {
      return new StepResult.Park();
    }
    return new StepResult.Continue(new Output(baseline.isEmpty()));
  }

  /**
   * In one transaction: stores the diff on the run. Returns false, and writes nothing, if the run
   * has become terminal.
   */
  private boolean save(String runId, String diffJson) {
    Boolean saved =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  run.setDiffSummary(diffJson);
                  return true;
                })
                .orElse(false));
    return Boolean.TRUE.equals(saved);
  }

  private SpecSnapshot snapshot(GenerationRun run) {
    String id = Objects.requireNonNull(run.getSpecSnapshotId(), "run has no spec snapshot");
    return snapshots
        .findById(id)
        .orElseThrow(() -> new IllegalStateException("spec snapshot not found: " + id));
  }
}
