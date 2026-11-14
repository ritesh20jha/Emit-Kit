package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.StepName;
import dev.emitkit.vcs.VcsClient;
import java.util.Optional;

/**
 * What a step runs with: the run, its project and config, a VCS client acting as the project's
 * creator, a logger, and the outputs of the steps that already ran. The runner builds one for each
 * step.
 */
public final class RunContext {

  private final GenerationRun run;
  private final Project project;
  private final ProjectConfig config;
  private final VcsClient vcs;
  private final RunLogger log;
  private final CancellationRegistry cancellation;

  RunContext(
      GenerationRun run,
      Project project,
      ProjectConfig config,
      VcsClient vcs,
      RunLogger log,
      CancellationRegistry cancellation) {
    this.run = run;
    this.project = project;
    this.config = config;
    this.vcs = vcs;
    this.log = log;
    this.cancellation = cancellation;
  }

  /** The run, as the runner last saved it. */
  public GenerationRun run() {
    return run;
  }

  public Project project() {
    return project;
  }

  public ProjectConfig config() {
    return config;
  }

  public VcsClient vcs() {
    return vcs;
  }

  public RunLogger log() {
    return log;
  }

  /** Throws {@link CancelledException} if the run has been asked to stop. */
  public void checkCancelled() {
    cancellation.checkCancelled(run.getId());
  }

  /** The output an earlier step saved, deserialized as {@code type}. Empty if it saved none. */
  public <T> Optional<T> output(StepName step, Class<T> type) {
    return StepOutputs.read(run.getStepOutputs(), step, type);
  }
}
