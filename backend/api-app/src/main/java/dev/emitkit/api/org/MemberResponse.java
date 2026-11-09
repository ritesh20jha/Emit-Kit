package dev.emitkit.api.org;

import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;

/**
 * A member of an organization. {@code roleSource} tells whether the role came from sync or was set
 * by hand.
 */
public record MemberResponse(
    String userId, String login, String name, String avatarUrl, Role role, RoleSource roleSource) {}
