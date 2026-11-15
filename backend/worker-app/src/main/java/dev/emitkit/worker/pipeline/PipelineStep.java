package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.StepName;

/** One step of the generation pipeline. Steps are Spring beans, one per {@link StepName}. */
public interface PipelineStep {

  /** The step this bean implements. */
  StepName name();

  /**
   * Runs the step. An {@link dev.emitkit.common.error.AppException} fails the run. A {@link
   * dev.emitkit.common.error.TransientException} propagates, so the worker can retry the run.
   */
  StepResult execute(RunContext ctx) throws Exception;
}
