package dev.emitkit.worker.pipeline;

import dev.emitkit.common.error.ErrorCode;
import org.springframework.lang.Nullable;

/** What a {@link PipelineStep} reports when it returns. */
public sealed interface StepResult {

  /** The step finished. {@code output} is saved as JSON under the step's name. */
  record Continue(@Nullable Object output) implements StepResult {}

  /** The run stops here. The step has already saved its own status change. */
  record Park() implements StepResult {}

  /** The step failed, so the run fails with {@code code} and {@code message}. */
  record Fail(ErrorCode code, String message) implements StepResult {}
}
