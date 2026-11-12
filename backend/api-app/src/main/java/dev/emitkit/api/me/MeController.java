package dev.emitkit.api.me;

import dev.emitkit.api.auth.CurrentUser;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints for the signed-in user. Bodies are stubs until the feature tickets replace them. */
@RestController
@RequestMapping("/api/v1/me")
@Tag(name = "Me", description = "The signed-in user, their organizations and VCS quota.")
public class MeController {

  private final UserRepository users;
  private final OrgMemberRepository orgMembers;
  private final OrganizationRepository organizations;

  public MeController(
      UserRepository users, OrgMemberRepository orgMembers, OrganizationRepository organizations) {
    this.users = users;
    this.orgMembers = orgMembers;
    this.organizations = organizations;
  }

  @GetMapping
  @Operation(summary = "Get the signed-in user and their organizations")
  public MeResponse me(@CurrentUser EmitKitUser principal) {
    User user =
        users
            .findById(principal.userId())
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "user not found"));
    List<OrgMember> memberships = orgMembers.findByUserId(user.getId());
    Map<String, Organization> orgsById =
        organizations.findAllById(memberships.stream().map(OrgMember::getOrgId).toList()).stream()
            .collect(Collectors.toMap(Organization::getId, org -> org));
    List<OrgMembership> orgs =
        memberships.stream()
            .map(membership -> {
              Organization org = orgsById.get(membership.getOrgId());
              return new OrgMembership(
                  org.getId(),
                  org.getSlug(),
                  org.getName(),
                  org.isPersonal(),
                  membership.getRole());
            })
            .toList();
    return new MeResponse(user.getId(), user.getLogin(), user.getName(), user.getAvatarUrl(), orgs);
  }

  @GetMapping("/vcs-quota")
  @Operation(summary = "Get the signed-in user's remaining VCS API quota")
  public QuotaResponse vcsQuota() {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/sync-orgs")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Re-sync the signed-in user's organizations from the VCS provider")
  public void syncOrgs() {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
