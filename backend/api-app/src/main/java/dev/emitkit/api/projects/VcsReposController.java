package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Repositories on the VCS host that a project can be created from. Bodies are stubs for now. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/vcs-repos")
@Tag(name = "VCS repositories", description = "Repositories the user can pick for a project.")
public class VcsReposController {

  @GetMapping
  @Operation(summary = "List the repositories the user can see, optionally filtered by name")
  public List<RepoOption> listRepos(
      @PathVariable("orgId") String orgId,
      @RequestParam(name = "q", required = false) @Nullable String q) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/{owner}/{repo}/spec-check")
  @Operation(summary = "Check that a spec file in a repository parses and is valid")
  public SpecCheckResponse checkSpec(
      @PathVariable("orgId") String orgId,
      @PathVariable("owner") String owner,
      @PathVariable("repo") String repo,
      @RequestParam(name = "path") String path,
      @RequestParam(name = "branch", required = false) @Nullable String branch) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
