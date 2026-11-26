package dev.emitkit.domain;

/**
 * Where a membership role came from. {@code MANUAL} roles set in EmitKit are not overwritten by a
 * later GitHub sync. Stored by name in {@code org_members.role_source}.
 */
public enum RoleSource {
  SYNC,
  MANUAL
}
