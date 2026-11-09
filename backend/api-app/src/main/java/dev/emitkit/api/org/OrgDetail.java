package dev.emitkit.api.org;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/** One organization in full, with the signed-in user's role in it. */
public record OrgDetail(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String slug,
    @Schema(requiredMode = REQUIRED) String name,
    @Schema(requiredMode = REQUIRED) boolean isPersonal,
    /** Null for a personal org, which has no VCS organization behind it. */
    @Nullable String vcsOrgId,
    @Schema(requiredMode = REQUIRED) int memberCount,
    @Schema(requiredMode = REQUIRED) Role role) {}
