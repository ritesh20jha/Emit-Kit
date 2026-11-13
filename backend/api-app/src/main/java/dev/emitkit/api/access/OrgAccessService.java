package dev.emitkit.api.access;

import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberId;
import dev.emitkit.domain.OrgMemberRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Checks that the signed-in user may perform a permission in an organization (spec v1 section 3).
 */
@Service
public class OrgAccessService {

  private final OrgMemberRepository orgMembers;

  public OrgAccessService(OrgMemberRepository orgMembers) {
    this.orgMembers = orgMembers;
  }

  /**
   * Returns the signed-in user's membership in {@code orgId} when their role grants {@code p}.
   *
   * @throws AppException NOT_FOUND when the user is not a member, which also covers an org that
   *     does not exist, so the org's existence is not revealed; FORBIDDEN when the member's role
   *     lacks {@code p}
   */
  public Membership require(String orgId, Permission p) {
    String userId = currentUserId();
    OrgMember member =
        orgMembers
            .findById(new OrgMemberId(orgId, userId))
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "organization not found"));
    if (!RolePermissions.of(member.getRole()).contains(p)) {
      throw new AppException(ErrorCode.FORBIDDEN, "your role does not allow " + p);
    }
    return new Membership(orgId, userId, member.getRole());
  }

  private static String currentUserId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof EmitKitUser user) {
      return user.userId();
    }
    // The security chain answers 401 before a controller runs, so this is a wiring bug.
    throw new IllegalStateException("no EmitKitUser principal on the security context");
  }
}
