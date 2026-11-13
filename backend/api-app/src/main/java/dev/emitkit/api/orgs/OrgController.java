package dev.emitkit.api.orgs;

import dev.emitkit.api.access.Membership;
import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.api.auth.CurrentUser;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizations the signed-in user belongs to. */
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Orgs", description = "Organizations the signed-in user belongs to.")
public class OrgController {

  private final OrgAccessService orgAccess;
  private final OrgMemberRepository orgMembers;
  private final OrganizationRepository organizations;

  public OrgController(
      OrgAccessService orgAccess,
      OrgMemberRepository orgMembers,
      OrganizationRepository organizations) {
    this.orgAccess = orgAccess;
    this.orgMembers = orgMembers;
    this.organizations = organizations;
  }

  @GetMapping
  @Operation(summary = "List the organizations the signed-in user belongs to")
  public List<OrgSummary> listOrgs(@CurrentUser EmitKitUser user) {
    List<OrgMember> memberships = orgMembers.findByUserId(user.userId());
    Map<String, Organization> orgsById =
        organizations.findAllById(memberships.stream().map(OrgMember::getOrgId).toList()).stream()
            .collect(Collectors.toMap(Organization::getId, org -> org));
    return memberships.stream()
        .map(membership -> {
          Organization org = orgsById.get(membership.getOrgId());
          return new OrgSummary(
              org.getId(),
              org.getSlug(),
              org.getName(),
              org.isPersonal(),
              membership.getRole(),
              memberCount(org.getId()));
        })
        .toList();
  }

  @GetMapping("/{orgId}")
  @Operation(summary = "Get one organization")
  public OrgDetail getOrg(@PathVariable("orgId") String orgId) {
    Membership membership = orgAccess.require(orgId, Permission.ORG_VIEW);
    Organization org =
        organizations
            .findById(orgId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "organization not found"));
    return new OrgDetail(
        org.getId(),
        org.getSlug(),
        org.getName(),
        org.isPersonal(),
        org.isPersonal() ? null : org.getVcsOrgId(),
        memberCount(orgId),
        membership.role());
  }

  private int memberCount(String orgId) {
    return (int) orgMembers.countByOrgId(orgId);
  }
}
