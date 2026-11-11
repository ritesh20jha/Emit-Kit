package dev.emitkit.api.runs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.FileStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One generated file. {@code content} or {@code unifiedDiff} is set, depending on the request. */
public record FileView(
    @Schema(requiredMode = REQUIRED) String path,
    @Schema(requiredMode = REQUIRED) FileStatus status,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String content,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String unifiedDiff) {}
