package dev.emitkit.api.access;

import static dev.emitkit.api.access.Permission.ORG_VIEW;
import static dev.emitkit.api.access.Permission.PROJECT_CREATE;
import static dev.emitkit.api.access.Permission.PROJECT_VIEW;
import static dev.emitkit.api.access.Permission.RUN_CANCEL;
import static dev.emitkit.api.access.Permission.RUN_RERUN;
import static dev.emitkit.api.access.Permission.RUN_TRIGGER;
import static dev.emitkit.api.access.Permission.RUN_VIEW;
import static dev.emitkit.api.access.Permission.SPEC_EDIT;
import static dev.emitkit.api.access.Permission.SPEC_VIEW;

import dev.emitkit.domain.Role;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** The permissions granted to each organization role (spec v1 section 3). */
public final class RolePermissions {

  private static final Map<Role, Set<Permission>> TABLE = new EnumMap<>(Role.class);

  static {
    TABLE.put(Role.ADMIN, Set.of(Permission.values()));
    TABLE.put(
        Role.EDITOR,
        Set.of(
            ORG_VIEW,
            PROJECT_VIEW,
            RUN_VIEW,
            SPEC_VIEW,
            PROJECT_CREATE,
            SPEC_EDIT,
            RUN_TRIGGER,
            RUN_CANCEL,
            RUN_RERUN));
    TABLE.put(Role.VIEWER, Set.of(ORG_VIEW, PROJECT_VIEW, RUN_VIEW, SPEC_VIEW));
  }

  private RolePermissions() {}

  /** Returns the unmodifiable set of permissions granted to {@code role}. */
  public static Set<Permission> of(Role role) {
    return TABLE.get(role);
  }
}
