package dev.emitkit.api.projects;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** One saved version of a spec. {@code infoVersion} is the spec's own version, if it has one. */
public record SnapshotSummary(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String contentHash,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String infoVersion,
    @Schema(requiredMode = REQUIRED) Instant createdAt) {}
