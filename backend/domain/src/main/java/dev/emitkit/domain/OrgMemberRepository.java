package dev.emitkit.domain;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Organization memberships, keyed by {@link OrgMemberId}. */
public interface OrgMemberRepository extends JpaRepository<OrgMember, OrgMemberId> {

  List<OrgMember> findByUserId(String userId);

  List<OrgMember> findByOrgId(String orgId);

  long countByOrgIdAndRole(String orgId, Role role);
}
