package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.StepName;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One line of a run's log. {@code seq} increases within a run and is the cursor for {@code
 * afterSeq}.
 */
public record LogLine(
    @Schema(requiredMode = REQUIRED) int seq,
    @Schema(requiredMode = REQUIRED) Instant ts,
    @Schema(requiredMode = REQUIRED, description = "INFO, WARN or ERROR") String level,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable StepName step,
    @Schema(requiredMode = REQUIRED) String message) {}
