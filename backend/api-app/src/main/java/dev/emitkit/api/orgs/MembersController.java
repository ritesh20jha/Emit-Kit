package dev.emitkit.api.orgs;

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

/** Members of an organization. Bodies are stubs until the feature tickets replace them. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/members")
@Tag(name = "Members", description = "Members of an organization and their roles.")
public class MembersController {

  @GetMapping
  @Operation(summary = "List the members of an organization")
  public List<MemberResponse> listMembers(@PathVariable("orgId") String orgId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PatchMapping("/{userId}")
  @Operation(summary = "Change a member's role")
  public MemberResponse updateMemberRole(
      @PathVariable("orgId") String orgId,
      @PathVariable("userId") String userId,
      @Valid @RequestBody UpdateRoleRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
