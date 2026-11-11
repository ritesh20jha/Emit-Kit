package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/** Identifies one operation: its operationId and the method and path it is served on. */
public record OpRef(
    @Schema(requiredMode = REQUIRED) String operationId,
    @Schema(requiredMode = REQUIRED) String method,
    @Schema(requiredMode = REQUIRED) String path) {}
