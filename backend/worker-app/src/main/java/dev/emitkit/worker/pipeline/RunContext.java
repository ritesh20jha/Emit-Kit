package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.StepName;
import dev.emitkit.vcs.VcsClient;
import java.util.Optional;
import java.util.function.Supplier;

/** What a step runs with. The runner builds one for each step. */
public final class RunContext {

  private final GenerationRun run;
  private final Project project;
  private final ProjectConfig config;
  private final Supplier<VcsClient> vcs;
  private final RunLogger log;
  private final CancellationRegistry cancellation;

  public RunContext(
      GenerationRun run,
      Project project,
      ProjectConfig config,
      Supplier<VcsClient> vcs,
      RunLogger log,
      CancellationRegistry cancellation) {
    this.run = run;
    this.project = project;
    this.config = config;
    this.vcs = vcs;
    this.log = log;
    this.cancellation = cancellation;
  }

  /**
   * A read-only snapshot taken when the step started. Never save it: a save writes every column and
   * clobbers newer values such as {@code cancel_requested}.
   */
  public GenerationRun run() {
    return run;
  }

  public Project project() {
    return project;
  }

  public ProjectConfig config() {
    return config;
  }

  /** The creator's VCS client, created on first use. */
  public VcsClient vcs() {
    return vcs.get();
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
