package dev.emitkit.api.me;

import dev.emitkit.api.auth.CurrentUser;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.api.orgs.OrgSyncService;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.OrgMember;
import dev.emitkit.domain.OrgMemberRepository;
import dev.emitkit.domain.Organization;
import dev.emitkit.domain.OrganizationRepository;
import dev.emitkit.domain.User;
import dev.emitkit.domain.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
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

  /** How long a manual org sync blocks the next one for the same user. */
  private static final Duration SYNC_INTERVAL = Duration.ofSeconds(60);

  private static final String SYNC_THROTTLE_PREFIX = "orgsync:throttle:";

  private final UserRepository users;
  private final OrgMemberRepository orgMembers;
  private final OrganizationRepository organizations;
  private final OrgSyncService orgSync;
  private final StringRedisTemplate redis;

  public MeController(
      UserRepository users,
      OrgMemberRepository orgMembers,
      OrganizationRepository organizations,
      OrgSyncService orgSync,
      StringRedisTemplate redis) {
    this.users = users;
    this.orgMembers = orgMembers;
    this.organizations = organizations;
    this.orgSync = orgSync;
    this.redis = redis;
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

  /**
   * Runs an org sync on demand. The first call in each minute claims a Redis key with SET NX EX and
   * syncs; a call while the key lives is refused. The login-triggered sync does not check this key,
   * so a login always syncs.
   */
  @PostMapping("/sync-orgs")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Re-sync the signed-in user's organizations from the VCS provider")
  @ApiResponse(responseCode = "429", description = "A sync ran less than a minute ago")
  public void syncOrgs(@CurrentUser EmitKitUser principal) {
    Boolean claimed =
        redis
            .opsForValue()
            .setIfAbsent(SYNC_THROTTLE_PREFIX + principal.userId(), "1", SYNC_INTERVAL);
    if (!Boolean.TRUE.equals(claimed)) {
      throw new AppException(ErrorCode.RATE_LIMITED, "Sync ran less than a minute ago");
    }
    orgSync.sync(principal.userId());
  }
}
