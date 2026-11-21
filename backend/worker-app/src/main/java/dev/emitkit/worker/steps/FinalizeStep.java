package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.FINALIZE;

import dev.emitkit.domain.ChangeType;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.SdkVersion;
import dev.emitkit.domain.SdkVersionRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepResult;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Marks the run SUCCESS, records the version it released and closes the log (spec v1 §5.1 step 11).
 * A version is recorded once per project, so a rerun adds no second row.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class FinalizeStep implements PipelineStep {

  private final WorkDir workDir;
  private final GenerationRunRepository runs;
  private final SdkVersionRepository sdkVersions;
  private final TransactionTemplate transactions;
  private final InstantSource clock;

  @Autowired
  public FinalizeStep(
      WorkDir workDir,
      GenerationRunRepository runs,
      SdkVersionRepository sdkVersions,
      PlatformTransactionManager transactionManager) {
    this(workDir, runs, sdkVersions, transactionManager, InstantSource.system());
  }

  FinalizeStep(
      WorkDir workDir,
      GenerationRunRepository runs,
      SdkVersionRepository sdkVersions,
      PlatformTransactionManager transactionManager,
      InstantSource clock) {
    this.workDir = workDir;
    this.runs = runs;
    this.sdkVersions = sdkVersions;
    this.transactions = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public StepName name() {
    return FINALIZE;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    String runId = run.getId();
    CalcVersionStep.Output calc =
        ctx.output(StepName.CALC_VERSION, CalcVersionStep.Output.class)
            .orElseThrow(() -> new IllegalStateException("CALC_VERSION saved no output"));
    boolean release = calc.changed() && !run.isDryRun();
    String changelog =
        release ? PrBody.changelog(CommitOutputStep.parseDiff(run.getDiffSummary())) : null;

    Optional<Boolean> recorded = finish(runId, calc.version(), release, changelog);
    if (recorded.isEmpty()) {
      // The run is already terminal: whoever finished it owns the log and the work dir.
      return new StepResult.Park();
    }
    ctx.log().done();
    workDir.delete(runId);
    return new StepResult.Continue(new Output(calc.version(), recorded.get()));
  }

  /**
   * In one transaction, on the locked run: sets SUCCESS and inserts the version when it is to be
   * released and not yet recorded. Empty, writing nothing, if the run has become terminal.
   */
  private Optional<Boolean> finish(
      String runId, String version, boolean release, String changelog) {
    return transactions.execute(tx ->
        runs.findByIdForUpdate(runId)
            .filter(run -> !run.getStatus().isTerminal())
            .map(run -> {
              run.setStatus(RunStatus.SUCCESS);
              run.setFinishedAt(clock.instant());
              return release && insertVersion(run, version, changelog);
            }));
  }

  /** Inserts the version unless the project already released it. */
  private boolean insertVersion(GenerationRun run, String version, String changelog) {
    String projectId = run.getProjectId();
    if (sdkVersions.existsByProjectIdAndVersion(projectId, version)) {
      return false;
    }
    ChangeType changeType = Objects.requireNonNull(run.getChangeType(), "changed run has no type");
    sdkVersions.save(
        new SdkVersion(
            null, projectId, version, run.getId(), changeType, changelog, clock.instant()));
    return true;
  }

  /** The version the run ended with, and whether this run recorded it. */
  public record Output(String version, boolean versionRecorded) {}
}
