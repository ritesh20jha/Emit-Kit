package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The changes made to one operation that exists in both versions of the spec. */
public record OpChange(
    @Schema(requiredMode = REQUIRED) OpRef op,
    @Schema(requiredMode = REQUIRED) List<Change> changes) {}
