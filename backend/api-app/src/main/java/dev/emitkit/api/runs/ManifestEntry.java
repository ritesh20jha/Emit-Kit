package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.FileStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** One line of a run's file manifest: the file's path, content hash, size and status. */
public record ManifestEntry(
    @Schema(requiredMode = REQUIRED) String path,
    @Schema(requiredMode = REQUIRED) String sha256,
    @Schema(requiredMode = REQUIRED) long size,
    @Schema(requiredMode = REQUIRED) FileStatus status) {}
