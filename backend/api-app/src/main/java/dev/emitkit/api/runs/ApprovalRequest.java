package dev.emitkit.api.runs;

import dev.emitkit.domain.Decision;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** A reviewer's decision on a run that waits for approval. The comment is optional. */
public record ApprovalRequest(
    @NotNull Decision decision,
    @Size(max = 2000) @Schema(types = {"string", "null"}) @Nullable String comment) {}
