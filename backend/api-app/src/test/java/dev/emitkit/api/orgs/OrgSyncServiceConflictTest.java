package dev.emitkit.api.orgs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsOrg;
import dev.emitkit.vcs.VcsOrg.VcsRole;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.ratelimit.RateLimitPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;

/** The retry on a concurrent write conflict, without a Spring context. */
class OrgSyncServiceConflictTest {

  private final VcsProvider vcsProvider = mock(VcsProvider.class);
  private final VcsClient client = mock(VcsClient.class);
  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrgMemberRepository orgMembers = mock(OrgMemberRepository.class);
  private OrgSyncService sync;

  @BeforeEach
  void setUp() {
    when(vcsProvider.forProject(anyString(), any(RateLimitPolicy.class))).thenReturn(client);
    when(client.listOrgs())
        .thenReturn(List.of(new VcsOrg("id-acme", "acme", null, false, VcsRole.OWNER)));
    when(organizations.findByVcsProviderAndVcsOrgId("github", "id-acme"))
        .thenReturn(
            Optional.of(
                new Organization("org-1", "github", "id-acme", "acme", "acme", false, null)));
    when(orgMembers.findByUserId("user-1")).thenReturn(List.of());
    sync =
        new OrgSyncService(
            vcsProvider, organizations, orgMembers, mock(PlatformTransactionManager.class));
  }

  @Test
  void conflictInDbPhase_isRetriedOnceWithAFreshRead() {
    doThrow(new DataIntegrityViolationException("duplicate membership"))
        .doAnswer(call -> call.getArgument(0))
        .when(orgMembers)
        .save(any());

    sync.sync("user-1");

    verify(client, times(1)).listOrgs();
    verify(orgMembers, times(2)).findByUserId("user-1");
    verify(orgMembers, times(2)).save(any());
  }

  @Test
  void optimisticLockConflict_isRetriedOnce() {
    doThrow(new ObjectOptimisticLockingFailureException(OrgMember.class, "user-1"))
        .doAnswer(call -> call.getArgument(0))
        .when(orgMembers)
        .save(any());

    sync.sync("user-1");

    verify(orgMembers, times(2)).findByUserId("user-1");
  }

  @Test
  void conflictTwice_propagates() {
    doThrow(new DataIntegrityViolationException("duplicate membership"))
        .when(orgMembers)
        .save(any());

    assertThatThrownBy(() -> sync.sync("user-1"))
        .isInstanceOf(DataIntegrityViolationException.class);
    verify(orgMembers, times(2)).findByUserId("user-1");
  }

  @Test
  void otherFailure_isNotRetried() {
    doThrow(new IllegalStateException("boom")).when(orgMembers).save(any());

    assertThatThrownBy(() -> sync.sync("user-1")).isInstanceOf(IllegalStateException.class);
    verify(orgMembers, times(1)).findByUserId("user-1");
  }
}
