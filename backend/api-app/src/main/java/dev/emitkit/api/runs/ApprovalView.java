package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Decision;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The approval of a run. All fields are null until a reviewer decides. */
public record ApprovalView(
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Decision decision,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String decidedBy,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String comment,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable Instant decidedAt) {}
