package dev.emitkit.api.me;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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

  @GetMapping
  @Operation(summary = "Get the signed-in user and their organizations")
  public MeResponse me() {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
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
