package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.id.Ids;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class DB01RepositoriesIT extends PostgresIT {

  /** Crockford base32 without I, L, O, U: the ULID alphabet. */
  private static final String ULID_PATTERN = "[0-9A-HJKMNP-TV-Z]{26}";

  /** A valid ULID (the example from the ULID spec), used as a pre-set id. */
  private static final String PRESET_ID = "01ARZ3NDEKTSV4RRFFQ69G5FAV";

  @Autowired private UserRepository users;
  @Autowired private OAuthAccountRepository oauth;
  @Autowired private OrganizationRepository orgs;
  @Autowired private OrgMemberRepository members;
  @Autowired private JdbcTemplate jdbc;

  @PersistenceContext private EntityManager em;

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
  void invalidRoleSource_rejectedByCheckConstraint() {
    User user = users.saveAndFlush(newUser(9L));
    Organization org = orgs.saveAndFlush(newOrg("102", "delta"));
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO org_members (org_id, user_id, role, role_source)"
                    + " VALUES (?, ?, 'ADMIN', 'BOGUS')",
                org.getId(),
                user.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("org_members_role_source_check");
  }

  @Test
  void organizationInsertedWithoutVcsProvider_defaultsToGithub() {
    jdbc.update(
        "INSERT INTO organizations (id, vcs_org_id, slug, name, is_personal, created_at)"
            + " VALUES (?, '555', 'raw', 'raw', false, now())",
        PRESET_ID);
    assertThat(
            jdbc.queryForObject(
                "SELECT vcs_provider FROM organizations WHERE id = ?", String.class, PRESET_ID))
        .isEqualTo("github");
  }

  @Test
  void idIsUlidAssignedBeforeInsert() {
    assertThat(users.save(newUser(4L)).getId()).hasSize(26);
  }

  @Test
  void constructorAssignedId_isValidUlid_beforeAndAfterSave() {
    User user = newUser(5L);
    String idBeforeSave = user.getId();
    assertThat(idBeforeSave).matches(ULID_PATTERN);
    assertThat(users.saveAndFlush(user).getId()).isEqualTo(idBeforeSave);
  }

  @Test
  void presetId_isKeptBySave() {
    User user = new User(PRESET_ID, 6L, "octocat6", "Octo Cat", "octo@example.com", "https://a");
    assertThat(user.getId()).isEqualTo(PRESET_ID);
    assertThat(users.saveAndFlush(user).getId()).isEqualTo(PRESET_ID);
  }

  @Test
  void saveNewEntityWithPresetId_returnsSameInstance_andKeepsCreatedAt() {
    User user =
        new User(Ids.newUlid(), 7L, "octocat7", "Octo Cat", "octo@example.com", "https://a");
    assertThat(users.saveAndFlush(user)).isSameAs(user);
    assertThat(user.getCreatedAt()).isNotNull();
  }

  @Test
  void saveNewOrgMember_returnsSameInstance() {
    User user = users.saveAndFlush(newUser(8L));
    Organization org = orgs.saveAndFlush(newOrg("103", "epsilon"));
    OrgMember member = new OrgMember(org.getId(), user.getId(), Role.VIEWER, RoleSource.SYNC);
    assertThat(members.saveAndFlush(member)).isSameAs(member);
  }

  @Test
  void userWithNullNameAndEmail_roundTrips() {
    users.saveAndFlush(new User(null, 10L, "ghost", null, null, "https://a"));
    em.clear();
    assertThat(users.findByGithubId(10L))
        .hasValueSatisfying(user -> {
          assertThat(user.getName()).isNull();
          assertThat(user.getEmail()).isNull();
        });
  }

  @Test
  void organizationWithNullVcsProvider_defaultsToGithub() {
    orgs.saveAndFlush(new Organization(null, null, "500", "nullprov", "nullprov", false, null));
    em.clear();
    assertThat(orgs.findByVcsProviderAndVcsOrgId("github", "500"))
        .hasValueSatisfying(org -> assertThat(org.getVcsProvider()).isEqualTo("github"));
  }

  @Test
  void oauthAccountWithUnknownUser_violatesForeignKey() {
    assertThatThrownBy(() ->
            jdbc.update(
                "INSERT INTO oauth_accounts (id, user_id, provider, provider_account_id,"
                    + " access_token_enc, scopes, updated_at)"
                    + " VALUES (?, ?, 'github', '77', 'vault:v1:x', 'repo', now())",
                PRESET_ID,
                Ids.newUlid()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("oauth_accounts_user_id_fkey");
  }

  private static User newUser(long githubId) {
    return new User(
        null, githubId, "octocat" + githubId, "Octo Cat", "octo@example.com", "https://a");
  }

  private static OAuthAccount account(User user, String provider, String providerAccountId) {
    return new OAuthAccount(
        null, user.getId(), provider, providerAccountId, "vault:v1:token", "repo");
  }

  private static Organization newOrg(String vcsOrgId, String slug) {
    return new Organization(null, "github", vcsOrgId, slug, slug, false, null);
  }
}
