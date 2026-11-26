package dev.emitkit.api.me;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** One organization the signed-in user belongs to, with their role in it. */
public record OrgMembership(
    @Schema(requiredMode = REQUIRED) String orgId,
    @Schema(requiredMode = REQUIRED) String slug,
    @Schema(requiredMode = REQUIRED) String name,
    @Schema(requiredMode = REQUIRED) boolean isPersonal,
    @Schema(requiredMode = REQUIRED) Role role) {}
