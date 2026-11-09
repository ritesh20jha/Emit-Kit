package dev.emitkit.api.orgs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;

/** An organization in the signed-in user's list, with their role and the member count. */
public record OrgSummary(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String slug,
    @Schema(requiredMode = REQUIRED) String name,
    @Schema(requiredMode = REQUIRED) boolean isPersonal,
    @Schema(requiredMode = REQUIRED) Role role,
    @Schema(requiredMode = REQUIRED) int memberCount) {}
