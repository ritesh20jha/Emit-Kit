package dev.emitkit.api.org;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Organizations and their members. Bodies are stubs until the feature tickets replace them. */
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Orgs", description = "Organizations the signed-in user belongs to, and their members.")
public class OrgController {

  @GetMapping
  @Operation(summary = "List the organizations the signed-in user belongs to")
  public List<OrgSummary> listOrgs() {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/{orgId}")
  @Operation(summary = "Get one organization")
  public OrgDetail getOrg(@PathVariable("orgId") String orgId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/{orgId}/members")
  @Operation(summary = "List the members of an organization")
  public List<MemberResponse> listMembers(@PathVariable("orgId") String orgId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PatchMapping("/{orgId}/members/{userId}")
  @Operation(summary = "Change a member's role")
  public MemberResponse updateMemberRole(
      @PathVariable("orgId") String orgId,
      @PathVariable("userId") String userId,
      @Valid @RequestBody UpdateRoleRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
