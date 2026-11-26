package dev.emitkit.api.orgs;

import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsOrg.VcsRole;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Mirrors a user's GitHub organizations and roles into EmitKit. One sync upserts every organization
 * GitHub returns, keyed by provider and VCS id. It gives the user a {@code SYNC} membership with
 * the role GitHub grants (owner becomes {@code ADMIN}, member becomes {@code EDITOR}). A {@code
 * MANUAL} membership keeps the role set in EmitKit. A {@code SYNC} membership in an organization
 * GitHub no longer returns is deleted. {@code MANUAL} memberships are never deleted.
 */
@Service
public class OrgSyncService {

  /** The value stored in {@code organizations.vcs_provider}. */
  private static final String PROVIDER = "github";

  private final VcsProvider vcsProvider;
  private final OrganizationRepository organizations;
  private final OrgMemberRepository orgMembers;
  private final TransactionTemplate transactions;

  public OrgSyncService(
      VcsProvider vcsProvider,
      OrganizationRepository organizations,
      OrgMemberRepository orgMembers,
      PlatformTransactionManager transactionManager) {
    this.vcsProvider = vcsProvider;
    this.organizations = organizations;
    this.orgMembers = orgMembers;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Syncs the organizations of {@code userId} with what GitHub returns for the user's token. The
   * call is idempotent. GitHub is called first, with no transaction open, so no database connection
   * is held across the network call. The rows are then written in one transaction. A VCS failure
   * propagates before any row is written, so the sync leaves the database as it was. A write
   * conflict with a concurrent sync of the same user (a duplicate insert or a stale row) rolls back
   * the transaction; the rows are then re-read and written once more, with the same GitHub result.
   */
  public void sync(String userId) {
    List<VcsOrg> remote = vcsProvider.forProject(userId, RateLimitPolicy.INTERACTIVE).listOrgs();
    try {
      transactions.executeWithoutResult(status -> apply(userId, remote));
    } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException conflict) {
      transactions.executeWithoutResult(status -> apply(userId, remote));
    }
  }

  /** Applies the sync rules to the rows of {@code userId}. Runs inside the transaction. */
  private void apply(String userId, List<VcsOrg> remote) {
    Map<String, OrgMember> current = new HashMap<>();
    for (OrgMember membership : orgMembers.findByUserId(userId)) {
      current.put(membership.getOrgId(), membership);
    }
    Set<String> returned = new HashSet<>();
    for (VcsOrg vcsOrg : remote) {
      Organization org = upsertOrganization(userId, vcsOrg);
      returned.add(org.getId());
      Role role = roleOf(vcsOrg.role());
      OrgMember membership = current.get(org.getId());
      if (membership == null) {
        orgMembers.save(new OrgMember(org.getId(), userId, role, RoleSource.SYNC));
      } else if (membership.getRoleSource() == RoleSource.SYNC) {
        membership.setRole(role);
      }
    }

    List<OrgMember> dropped =
        current.values().stream()
            .filter(m -> m.getRoleSource() == RoleSource.SYNC)
            .filter(m -> !returned.contains(m.getOrgId()))
            .toList();
    orgMembers.deleteAll(dropped);
  }

  /**
   * Creates the organization, or renames it when its login or name changed on GitHub. A new
   * personal organization is owned by {@code userId}; a team organization has no owner.
   */
  private Organization upsertOrganization(String userId, VcsOrg vcsOrg) {
    String name = vcsOrg.name() != null ? vcsOrg.name() : vcsOrg.login();
    Organization org =
        organizations.findByVcsProviderAndVcsOrgId(PROVIDER, vcsOrg.id()).orElse(null);
    if (org == null) {
      String owner = vcsOrg.isPersonal() ? userId : null;
      return organizations.save(
          new Organization(
              null, PROVIDER, vcsOrg.id(), vcsOrg.login(), name, vcsOrg.isPersonal(), owner));
    }
    org.rename(vcsOrg.login(), name);
    return org;
  }

  private static Role roleOf(VcsRole role) {
    return switch (role) {
      case OWNER -> Role.ADMIN;
      case MEMBER -> Role.EDITOR;
    };
  }
}
