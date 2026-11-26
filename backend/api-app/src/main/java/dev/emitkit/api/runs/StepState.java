package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.StepName;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One pipeline step of a run. {@code durationMs} runs from the step's first to its last log line.
 */
public record StepState(
    @Schema(requiredMode = REQUIRED) StepName name,
    @Schema(requiredMode = REQUIRED) StepStatus status,
    @Schema(
            requiredMode = REQUIRED,
            types = {"integer", "null"})
        @Nullable Long durationMs) {

  public enum StepStatus {
    PENDING,
    RUNNING,
    DONE,
    SKIPPED,
    FAILED
  }
}
