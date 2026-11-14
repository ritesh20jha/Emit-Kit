package dev.emitkit.worker.pipeline;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.StepName;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the steps of a generation run in {@link StepName} order (spec v1 §5.1). Each step's output
 * is saved in its own transaction, so a run that stopped partway resumes at the first step without
 * a saved output.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class PipelineRunner {

  private static final Logger LOG = LoggerFactory.getLogger(PipelineRunner.class);

  private final Map<StepName, PipelineStep> steps;
  private final GenerationRunRepository runs;
  private final ProjectRepository projects;
  private final ProjectConfigRepository configs;
  private final VcsProvider vcsProvider;
  private final TransactionTemplate transactions;
  private final CancellationRegistry cancellation;
  private final Clock clock;

  @Autowired
  public PipelineRunner(
      List<PipelineStep> steps,
      GenerationRunRepository runs,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      VcsProvider vcsProvider,
      PlatformTransactionManager transactionManager,
      CancellationRegistry cancellation) {
    this(
        steps,
        runs,
        projects,
        configs,
        vcsProvider,
        transactionManager,
        cancellation,
        Clock.systemUTC());
  }

  PipelineRunner(
      List<PipelineStep> steps,
      GenerationRunRepository runs,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      VcsProvider vcsProvider,
      PlatformTransactionManager transactionManager,
      CancellationRegistry cancellation,
      Clock clock) {
    this.steps = indexSteps(steps);
    this.runs = runs;
    this.projects = projects;
    this.configs = configs;
    this.vcsProvider = vcsProvider;
    this.transactions = new TransactionTemplate(transactionManager);
    this.cancellation = cancellation;
    this.clock = clock;
  }

  /**
   * Runs a generation run from where it stopped. A run that has already finished does nothing, so a
   * redelivered message is harmless. Returns when the run fails, parks, or has no steps left.
   *
   * <p>An {@link AppException} from a step fails the run. A {@link TransientException} and a {@link
   * CancelledException} propagate, and the run stays {@code RUNNING} for the worker to handle.
   */
  public void run(String runId) {
    GenerationRun run =
        runs.findById(runId)
            .orElseThrow(
                () -> new AppException(ErrorCode.NOT_FOUND, "generation run not found: " + runId));
    if (run.getStatus().isTerminal()) {
      return;
    }
    String projectId = run.getProjectId();
    String configId = run.getConfigId();
    Project project =
        projects
            .findById(projectId)
            .orElseThrow(
                () -> new AppException(ErrorCode.NOT_FOUND, "project not found: " + projectId));
    ProjectConfig config =
        configs
            .findById(configId)
            .orElseThrow(
                () -> new AppException(ErrorCode.NOT_FOUND, "config not found: " + configId));
    VcsClient vcs = vcsProvider.forProject(project.getCreatedBy());
    try {
      Optional<GenerationRun> started =
          update(
              runId,
              r -> {
                if (r.getStartedAt() == null) {
                  r.setStartedAt(clock.instant());
                }
                r.setStatus(RunStatus.RUNNING);
              });
      if (started.isEmpty()) {
        return;
      }
      run = started.get();
      for (StepName name : StepName.values()) {
        if (StepOutputs.has(run.getStepOutputs(), name)) {
          LOG.debug("generation run {}: step {} already has a saved output, skipping", runId, name);
          continue;
        }
        RunContext ctx = new RunContext(run, project, config, vcs, RunLogger.NOOP, cancellation);
        ctx.checkCancelled();
        ctx.log().step(name);
        StepResult result = execute(steps.get(name), ctx);
        switch (result) {
          case StepResult.Continue c -> {
            Optional<GenerationRun> saved = update(runId, r -> saveOutput(r, name, c.output()));
            if (saved.isEmpty()) {
              return;
            }
            run = saved.get();
          }
          case StepResult.Park p -> {
            return;
          }
          case StepResult.Fail f -> {
            if (update(runId, r -> markFailed(r, name, f)).isPresent()) {
              ctx.log().error(f.message());
            }
            return;
          }
        }
      }
    } finally {
      cancellation.release(runId);
    }
  }

  /**
   * Applies {@code change} in its own transaction. Writes nothing and returns empty if terminal.
   */
  private Optional<GenerationRun> update(String runId, Consumer<GenerationRun> change) {
    return transactions.execute(tx -> {
      GenerationRun saved = runs.findById(runId).orElseThrow();
      if (saved.getStatus().isTerminal()) {
        return Optional.empty();
      }
      change.accept(saved);
      return Optional.of(saved);
    });
  }

  private void saveOutput(GenerationRun run, StepName step, @Nullable Object output) {
    run.setCurrentStep(step);
    run.setStepOutputs(StepOutputs.with(run.getStepOutputs(), step, output));
  }

  private void markFailed(GenerationRun run, StepName step, StepResult.Fail failure) {
    run.setCurrentStep(step);
    run.setStatus(RunStatus.FAILED);
    run.setFailure(failure.code().name(), failure.message());
    run.setFinishedAt(clock.instant());
  }

  /**
   * Runs one step. An {@link AppException} becomes a {@link StepResult.Fail}. Any other checked
   * exception is a bug in the step, so it is wrapped and propagated.
   */
  private static StepResult execute(PipelineStep step, RunContext ctx) {
    try {
      return step.execute(ctx);
    } catch (TransientException e) {
      throw e;
    } catch (AppException e) {
      return new StepResult.Fail(e.code(), e.getMessage());
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("step " + step.name() + " threw a checked exception", e);
    }
  }

  /**
   * Indexes the steps by name. Fails unless there is exactly one step for each {@link StepName}.
   */
  private static Map<StepName, PipelineStep> indexSteps(List<PipelineStep> steps) {
    Map<StepName, PipelineStep> byName = new EnumMap<>(StepName.class);
    List<StepName> duplicates = new ArrayList<>();
    for (PipelineStep step : steps) {
      if (byName.put(step.name(), step) != null) {
        duplicates.add(step.name());
      }
    }
    List<StepName> missing = new ArrayList<>();
    for (StepName name : StepName.values()) {
      if (!byName.containsKey(name)) {
        missing.add(name);
      }
    }
    if (!missing.isEmpty() || !duplicates.isEmpty()) {
      throw new IllegalStateException(
          "pipeline needs exactly one step bean per StepName; missing "
              + missing
              + ", duplicate "
              + duplicates);
    }
    return byName;
  }
}
