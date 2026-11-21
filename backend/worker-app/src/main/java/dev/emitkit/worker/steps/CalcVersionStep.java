package dev.emitkit.worker.steps;

import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.diff.SpecDiff;
import dev.emitkit.spec.version.VersionCalculator;
import dev.emitkit.spec.version.VersionResult;
import dev.emitkit.spec.version.VersionStrategy;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Chooses the version the run releases from the diff DIFF_SPEC stored and the project's latest
 * released version (spec v1 §5.1, step 5). The version and its change type are kept on the run.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class CalcVersionStep implements PipelineStep {

  private static final VersionCalculator CALCULATOR = new VersionCalculator();
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** What CALC_VERSION saves: the version, and whether it differs from the last one. */
  public record Output(String version, boolean changed) {}

  private final GenerationRunRepository runs;
  private final SpecSnapshotRepository snapshots;
  private final SdkVersionRepository sdkVersions;
  private final TransactionTemplate transactions;

  @Autowired
  public CalcVersionStep(
      GenerationRunRepository runs,
      SpecSnapshotRepository snapshots,
      SdkVersionRepository sdkVersions,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.snapshots = snapshots;
    this.sdkVersions = sdkVersions;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return StepName.CALC_VERSION;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    SpecDiff diff =
        JSON.readValue(
            Objects.requireNonNull(run.getDiffSummary(), "DIFF_SPEC has saved no diff"),
            SpecDiff.class);
    Optional<String> last =
        sdkVersions
            .findFirstByProjectIdOrderByCreatedAtDescIdDesc(run.getProjectId())
            .map(SdkVersion::getVersion);
    VersionStrategy strategy = VersionStrategy.valueOf(ctx.config().getVersionStrategy().name());
    VersionResult result = CALCULATOR.next(strategy, last, diff, snapshot(run).getInfoVersion());
    ChangeType changeType =
        result.changeType().map(change -> ChangeType.valueOf(change.name())).orElse(null);
    if (!save(run.getId(), result.version(), changeType)) {
      return new StepResult.Park();
    }
    return new StepResult.Continue(new Output(result.version(), result.changeType().isPresent()));
  }

  /**
   * In one transaction: stores the version and change type on the run. Returns false, and writes
   * nothing, if the run has become terminal.
   */
  private boolean save(String runId, String version, @Nullable ChangeType changeType) {
    Boolean saved =
        transactions.execute(tx ->
            runs.findByIdForUpdate(runId)
                .filter(run -> !run.getStatus().isTerminal())
                .map(run -> {
                  run.setSdkVersion(version);
                  run.setChangeType(changeType);
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
