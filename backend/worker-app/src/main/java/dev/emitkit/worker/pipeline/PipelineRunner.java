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
import dev.emitkit.worker.logs.RunLoggerFactory;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a generation run's steps in {@link StepName} order (spec v1 §5.1). Each write is its own
 * transaction, so a run that stopped partway resumes at the first step without a saved output.
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
  private final Function<String, RunLogger> loggers;
  private final InstantSource clock;

  @Autowired
  public PipelineRunner(
      List<PipelineStep> steps,
      GenerationRunRepository runs,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      VcsProvider vcsProvider,
      PlatformTransactionManager transactionManager,
      CancellationRegistry cancellation,
      RunLoggerFactory loggers) {
    this(
        steps,
        runs,
        projects,
        configs,
        vcsProvider,
        transactionManager,
        cancellation,
        loggers::create,
        InstantSource.system());
  }

  PipelineRunner(
      List<PipelineStep> steps,
      GenerationRunRepository runs,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      VcsProvider vcsProvider,
      PlatformTransactionManager transactionManager,
      CancellationRegistry cancellation,
      Function<String, RunLogger> loggers,
      InstantSource clock) {
    this.steps = indexSteps(steps);
    this.runs = runs;
    this.projects = projects;
    this.configs = configs;
    this.vcsProvider = vcsProvider;
    this.transactions = new TransactionTemplate(transactionManager);
    this.cancellation = cancellation;
    this.loggers = loggers;
    this.clock = clock;
  }

  /**
   * Runs a generation run from where it stopped. A finished run does nothing, so a redelivered
   * message is harmless. An {@link AppException} from a step fails the run. A {@link
   * TransientException} or a {@link CancelledException} propagates and leaves the run RUNNING.
   */
  public void run(String runId) {
    GenerationRun run = found(runs.findById(runId), "generation run " + runId);
    if (run.getStatus().isTerminal()) {
      return;
    }
    String projectId = run.getProjectId();
    String configId = run.getConfigId();
    Project project = found(projects.findById(projectId), "project " + projectId);
    ProjectConfig config = found(configs.findById(configId), "config " + configId);
    RunLogger log = loggers.apply(runId);
    // Resolved on first use inside a step, so a run whose steps never need VCS never asks.
    Supplier<VcsClient> vcs = once(() -> vcsProvider.forProject(project.getCreatedBy()));
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
        cancellation.checkCancelled(runId);
        log.step(name);
        // current_step names the running step, so it is written before the step runs.
        Optional<GenerationRun> entered = update(runId, r -> r.setCurrentStep(name));
        if (entered.isEmpty()) {
          return;
        }
        run = entered.get();
        StepResult result =
            execute(steps.get(name), new RunContext(run, project, config, vcs, log, cancellation));
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
              log.error(f.message());
            }
            log.done();
            return;
          }
        }
      }
    } finally {
      cancellation.release(runId);
    }
  }

  /**
   * Applies {@code change} in its own transaction, on a row locked and read there. Writes nothing
   * and returns empty if the run has become terminal, so a finished run is never written again.
   */
  private Optional<GenerationRun> update(String runId, Consumer<GenerationRun> change) {
    return transactions.execute(tx -> {
      GenerationRun saved = found(runs.findByIdForUpdate(runId), "generation run " + runId);
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

  /** Runs one step. An {@link AppException} becomes a {@link StepResult.Fail}. */
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

  private static <T> T found(Optional<T> row, String what) {
    return row.orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, what + " not found"));
  }

  /** Calls {@code supplier} on first use and returns that value from then on. */
  private static <T> Supplier<T> once(Supplier<T> supplier) {
    AtomicReference<T> value = new AtomicReference<>();
    return () -> value.updateAndGet(v -> v != null ? v : supplier.get());
  }

  /** Indexes the steps by name. Fails unless there is exactly one step per {@link StepName}. */
  private static Map<StepName, PipelineStep> indexSteps(List<PipelineStep> steps) {
    Map<StepName, PipelineStep> byName = new EnumMap<>(StepName.class);
    List<StepName> duplicates = new ArrayList<>();
    for (PipelineStep step : steps) {
      if (byName.put(step.name(), step) != null) {
        duplicates.add(step.name());
      }
    }
    List<StepName> missing =
        Arrays.stream(StepName.values()).filter(n -> !byName.containsKey(n)).toList();
    if (!missing.isEmpty() || !duplicates.isEmpty()) {
      throw new IllegalStateException(
          "one step bean per StepName is needed; missing " + missing + ", duplicate " + duplicates);
    }
    return byName;
  }
}
