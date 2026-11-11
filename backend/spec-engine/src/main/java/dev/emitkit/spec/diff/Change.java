package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One change inside an operation.
 *
 * @param location where the change is, e.g. {@code query.limit}, {@code body.owner.name} or {@code
 *     200.items[].id}
 * @param breaking true when existing clients can stop working because of the change
 */
public record Change(
    @Schema(requiredMode = REQUIRED) ChangeKind kind,
    @Schema(requiredMode = REQUIRED) String location,
    @Schema(requiredMode = REQUIRED) boolean breaking) {}
