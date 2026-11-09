package dev.emitkit.api.orgs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * One organization in full, with the signed-in user's role in it.
 *
 * @param vcsOrgId the VCS organization behind this org, or null for a personal org
 */
public record OrgDetail(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String slug,
    @Schema(requiredMode = REQUIRED) String name,
    @Schema(requiredMode = REQUIRED) boolean isPersonal,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String vcsOrgId,
    @Schema(requiredMode = REQUIRED) int memberCount,
    @Schema(requiredMode = REQUIRED) Role role) {}
