package dev.emitkit.api.org;

import dev.emitkit.domain.Role;

/** One organization in full, with the signed-in user's role in it. */
public record OrgDetail(
    String id,
    String slug,
    String name,
    boolean isPersonal,
    String vcsOrgId,
    int memberCount,
    Role role) {}
