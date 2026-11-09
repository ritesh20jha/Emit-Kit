package dev.emitkit.api.me;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The signed-in user and the organizations they belong to. */
public record MeResponse(
    @Schema(requiredMode = REQUIRED) String id,
    @Schema(requiredMode = REQUIRED) String login,
    @Nullable String name,
    @Schema(requiredMode = REQUIRED) String avatarUrl,
    @Schema(requiredMode = REQUIRED) List<OrgMembership> orgs) {}
