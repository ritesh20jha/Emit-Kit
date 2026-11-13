package dev.emitkit.api.access;

import static dev.emitkit.domain.Role.ADMIN;
import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.domain.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RolePermissionsTest {

  @ParameterizedTest
  @CsvSource({
    "ADMIN, ORG_VIEW, true",
    "ADMIN, MEMBER_ROLE_EDIT, true",
    "ADMIN, RUN_APPROVE, true",
    "EDITOR, PROJECT_CREATE, true",
    "EDITOR, SPEC_EDIT, true",
    "EDITOR, RUN_TRIGGER, true",
    "EDITOR, PROJECT_DELETE, false",
    "EDITOR, CONFIG_EDIT, false",
    "EDITOR, RUN_APPROVE, false",
    "EDITOR, MEMBER_ROLE_EDIT, false",
    "VIEWER, RUN_VIEW, true",
    "VIEWER, PROJECT_CREATE, false",
    "VIEWER, SPEC_EDIT, false",
    "VIEWER, RUN_TRIGGER, false"
  })
  void matchesSpecTable(Role role, Permission p, boolean allowed) {
    assertThat(RolePermissions.of(role).contains(p)).isEqualTo(allowed);
  }

  @Test
  void adminHasEverything() {
    assertThat(RolePermissions.of(ADMIN)).containsExactlyInAnyOrder(Permission.values());
  }
}
