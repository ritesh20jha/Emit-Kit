package dev.emitkit.api.orgs;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

/**
 * A member of an organization. {@code roleSource} tells whether the role came from sync or was set
 * by hand.
 *
 * @param name the display name, or null when the member has none
 */
public record MemberResponse(
    @Schema(requiredMode = REQUIRED) String userId,
    @Schema(requiredMode = REQUIRED) String login,
    @Schema(
            requiredMode = REQUIRED,
            types = {"string", "null"})
        @Nullable String name,
    @Schema(requiredMode = REQUIRED) String avatarUrl,
    @Schema(requiredMode = REQUIRED) Role role,
    @Schema(requiredMode = REQUIRED) RoleSource roleSource) {}
