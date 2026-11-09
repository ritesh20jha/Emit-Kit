package dev.emitkit.api.orgs;

import dev.emitkit.domain.Role;
import jakarta.validation.constraints.NotNull;

/** The new role for a member. */
public record UpdateRoleRequest(@NotNull Role role) {}
