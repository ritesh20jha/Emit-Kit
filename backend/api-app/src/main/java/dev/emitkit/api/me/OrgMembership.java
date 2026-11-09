package dev.emitkit.api.me;

import dev.emitkit.domain.Role;

/** One organization the signed-in user belongs to, with their role in it. */
public record OrgMembership(
    String orgId, String slug, String name, boolean isPersonal, Role role) {}
