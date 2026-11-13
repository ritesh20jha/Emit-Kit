package dev.emitkit.api.orgs;

import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberId;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.Role;
import dev.emitkit.domain.RoleSource;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Members of an organization and their roles. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
@Tag(name = "Members", description = "Members of an organization and their roles.")
public class MembersController {

  private final OrgAccessService orgAccess;
  private final OrgMemberRepository orgMembers;
  private final OrganizationRepository organizations;
  private final UserRepository users;

  public MembersController(
      OrgAccessService orgAccess,
      OrgMemberRepository orgMembers,
      OrganizationRepository organizations,
      UserRepository users) {
    this.orgAccess = orgAccess;
    this.orgMembers = orgMembers;
    this.organizations = organizations;
    this.users = users;
  }

  @GetMapping
  @Operation(summary = "List the members of an organization")
  public List<MemberResponse> listMembers(@PathVariable("orgId") String orgId) {
    orgAccess.require(orgId, Permission.ORG_VIEW);
    List<OrgMember> members = orgMembers.findByOrgId(orgId);
    Map<String, User> usersById =
        users.findAllById(members.stream().map(OrgMember::getUserId).toList()).stream()
            .collect(Collectors.toMap(User::getId, user -> user));
    return members.stream()
        .map(member -> toResponse(member, usersById.get(member.getUserId())))
        .toList();
  }

  @PatchMapping("/{userId}")
  @Operation(summary = "Change a member's role")
  @Transactional
  public MemberResponse updateMemberRole(
      @PathVariable("orgId") String orgId,
      @PathVariable("userId") String userId,
      @Valid @RequestBody UpdateRoleRequest request) {
    orgAccess.require(orgId, Permission.MEMBER_ROLE_EDIT);
    // Lock the org first: two admins demoting each other must not both pass the last-admin check.
    organizations
        .findByIdForUpdate(orgId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "organization not found"));
    OrgMember target =
        orgMembers
            .findById(new OrgMemberId(orgId, userId))
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "member not found"));
    Role newRole = request.role();
    boolean demotesAdmin = target.getRole() == Role.ADMIN && newRole != Role.ADMIN;
    if (demotesAdmin && orgMembers.countByOrgIdAndRole(orgId, Role.ADMIN) == 1) {
      throw new AppException(
          ErrorCode.VALIDATION_FAILED, "An organization needs at least one admin");
    }
    target.assignRole(newRole, RoleSource.MANUAL);
    User user =
        users
            .findById(userId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "member not found"));
    return toResponse(target, user);
  }

  private static MemberResponse toResponse(OrgMember member, User user) {
    return new MemberResponse(
        user.getId(),
        user.getLogin(),
        user.getName(),
        user.getAvatarUrl(),
        member.getRole(),
        member.getRoleSource());
  }
}
