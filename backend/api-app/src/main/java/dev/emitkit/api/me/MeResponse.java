package dev.emitkit.api.me;

import java.util.List;

/** The signed-in user and the organizations they belong to. */
public record MeResponse(
    String id, String login, String name, String avatarUrl, List<OrgMembership> orgs) {}
