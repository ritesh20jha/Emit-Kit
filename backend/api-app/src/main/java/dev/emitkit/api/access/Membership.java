package dev.emitkit.api.access;

import dev.emitkit.domain.Role;

/** The signed-in user's membership in an organization, as checked by {@link OrgAccessService}. */
public record Membership(String orgId, String userId, Role role) {}
