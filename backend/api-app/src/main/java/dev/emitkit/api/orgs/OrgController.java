package dev.emitkit.api.orgs;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organizations the signed-in user belongs to. Bodies are stubs until the feature tickets replace
 * them.
 */
@RestController
@RequestMapping("/api/v1/orgs")
@Tag(name = "Orgs", description = "Organizations the signed-in user belongs to.")
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
}
