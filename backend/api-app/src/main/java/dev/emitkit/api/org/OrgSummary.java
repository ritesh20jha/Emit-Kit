package dev.emitkit.api.org;

import dev.emitkit.domain.Role;

/** An organization in the signed-in user's list, with their role and the member count. */
public record OrgSummary(
    String id, String slug, String name, boolean isPersonal, Role role, int memberCount) {}
