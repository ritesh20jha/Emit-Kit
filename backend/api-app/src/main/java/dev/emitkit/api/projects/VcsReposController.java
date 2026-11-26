package dev.emitkit.api.projects;

import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.VcsRepo;
import dev.emitkit.vcs.error.VcsNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Repositories on the VCS host that a project can be created from. */
@RestController
@RequestMapping("/api/v1/orgs/{orgId}/vcs-repos")
@Tag(name = "VCS repositories", description = "Repositories the user can pick for a project.")
public class VcsReposController {

  /** The most repositories the picker returns for one query. */
  private static final int MAX_REPOS = 50;

  private static final SpecParser SPEC_PARSER = new SpecParser();

  private final OrgAccessService orgAccess;
  private final VcsProvider vcsProvider;

  public VcsReposController(OrgAccessService orgAccess, VcsProvider vcsProvider) {
    this.orgAccess = orgAccess;
    this.vcsProvider = vcsProvider;
  }

  @GetMapping
  @Operation(summary = "List the repositories the user can see, optionally filtered by name")
  public List<RepoOption> listRepos(
      @PathVariable("orgId") String orgId,
      @RequestParam(name = "q", required = false) @Nullable String q) {
    String userId = orgAccess.require(orgId, Permission.PROJECT_CREATE).userId();
    String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
    return vcsProvider.forProject(userId).listRepos().stream()
        .filter(repo -> repo.fullName().toLowerCase(Locale.ROOT).contains(needle))
        .limit(MAX_REPOS)
        .map(VcsReposController::toOption)
        .toList();
  }

  @GetMapping("/{owner}/{repo}/spec-check")
  @Operation(summary = "Check that a spec file in a repository parses and is valid")
  public SpecCheckResponse checkSpec(
      @PathVariable("orgId") String orgId,
      @PathVariable("owner") String owner,
      @PathVariable("repo") String repo,
      @RequestParam(name = "path") String path,
      @RequestParam(name = "branch", required = false) @Nullable String branch) {
    String userId = orgAccess.require(orgId, Permission.PROJECT_CREATE).userId();
    VcsClient vcs = vcsProvider.forProject(userId);
    String fullName = owner + "/" + repo;
    String ref = branch == null || branch.isBlank() ? defaultBranch(vcs, fullName) : branch;
    FileContent file;
    try {
      file = vcs.getFile(fullName, path, ref);
    } catch (VcsNotFoundException e) {
      return new SpecCheckResponse(
          false, false, null, null, List.of("spec file not found: " + path));
    }
    ParseResult parsed = SPEC_PARSER.parse(file.content());
    ApiModel model = parsed.model();
    return new SpecCheckResponse(
        true,
        parsed.ok(),
        model == null ? null : model.info().title(),
        model == null ? null : model.info().version(),
        parsed.errors());
  }

  private static String defaultBranch(VcsClient vcs, String fullName) {
    return vcs.listRepos().stream()
        .filter(repo -> repo.fullName().equals(fullName))
        .findFirst()
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "repository not found"))
        .defaultBranch();
  }

  private static RepoOption toOption(VcsRepo repo) {
    return new RepoOption(
        repo.fullName(), repo.owner(), repo.isPrivate(), repo.pushedAt(), repo.defaultBranch());
  }
}
