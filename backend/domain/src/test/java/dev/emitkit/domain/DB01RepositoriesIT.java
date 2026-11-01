package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DB01RepositoriesIT extends PostgresIT {

  @Autowired private UserRepository users;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private OrganizationRepository orgs;
  @Autowired private OrgMemberRepository members;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void duplicateOAuthAccount_violatesUnique() {
    User user = users.saveAndFlush(newUser(1L));
    oauth.saveAndFlush(account(user, "github", "42"));
    assertThatThrownBy(() -> oauth.saveAndFlush(account(user, "github", "42")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void duplicateOrg_violatesUnique() {
    orgs.saveAndFlush(newOrg("99", "acme"));
    assertThatThrownBy(() -> orgs.saveAndFlush(newOrg("99", "acme-copy")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void memberHasCompositeKey_andRoleRoundTrips() {
    User user = users.saveAndFlush(newUser(2L));
    Organization org = orgs.saveAndFlush(newOrg("100", "beta"));
    members.saveAndFlush(new OrgMember(org.getId(), user.getId(), Role.EDITOR, RoleSource.MANUAL));
    assertThat(members.findByUserId(user.getId()))
        .singleElement()
        .extracting(OrgMember::getRole, OrgMember::getRoleSource)
        .containsExactly(Role.EDITOR, RoleSource.MANUAL);
  }

  @Test
  void invalidRoleValue_rejectedByCheckConstraint() {
    User user = users.saveAndFlush(newUser(3L));
    Organization org = orgs.saveAndFlush(newOrg("101", "gamma"));
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO org_members (org_id, user_id, role, role_source)"
                    + " VALUES (?, ?, 'OWNER', 'MANUAL')",
                org.getId(),
                user.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("org_members_role_check");
  }

  @Test
  void idIsUlidAssignedBeforeInsert() {
    assertThat(users.save(newUser(4L)).getId()).hasSize(26);
  }

  private static User newUser(long githubId) {
    return new User(githubId, "octocat" + githubId, "Octo Cat", "octo@example.com", "https://a");
  }

  private static OAuthAccount account(User user, String provider, String providerAccountId) {
    return new OAuthAccount(user.getId(), provider, providerAccountId, "vault:v1:token", "repo");
  }

  private static Organization newOrg(String vcsOrgId, String slug) {
    return new Organization("github", vcsOrgId, slug, slug, false, null);
  }
}
