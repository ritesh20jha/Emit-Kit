package dev.emitkit.api.projects;

import dev.emitkit.api.access.Membership;
import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Reads and saves the API spec file of a project on its default branch. The repository is the only
 * store: no database row changes. A save names the blob sha it replaces, so a save based on an
 * older read is refused with a conflict that carries the current sha.
 */
@Service
public class SpecService {

  /** The largest spec accepted, in UTF-8 bytes. */
  static final int MAX_SPEC_BYTES = 2 * 1024 * 1024;

  static final String DEFAULT_MESSAGE = "Update API spec (EmitKit)";
  private static final String PROJECT_NOT_FOUND = "project not found";

  private final OrgAccessService orgAccess;
  private final VcsProvider vcsProvider;
  private final ProjectRepository projects;

  public SpecService(
      OrgAccessService orgAccess, VcsProvider vcsProvider, ProjectRepository projects) {
    this.orgAccess = orgAccess;
    this.vcsProvider = vcsProvider;
    this.projects = projects;
  }

  /**
   * Reads the spec of a project.
   *
   * @throws AppException NOT_FOUND when the project is missing or the user is not a member of its
   *     org; FORBIDDEN when the member's role lacks SPEC_VIEW; SPEC_NOT_FOUND when the spec file is
   *     not on the default branch
   */
  public SpecResponse get(String projectId) {
    Project project = findProject(projectId);
    Membership member = requireMember(project, Permission.SPEC_VIEW);
    VcsClient vcs = vcsProvider.forProject(member.userId());
    FileContent file = readSpec(vcs, project);
    return new SpecResponse(file.content(), file.sha(), formatOf(project.getSpecPath()));
  }

  /**
   * Saves the spec of a project as one commit on the default branch. The request's {@code generate}
   * flag is accepted and ignored here; generation runs are started elsewhere.
   *
   * @throws AppException NOT_FOUND or FORBIDDEN as for {@link #get}, with SPEC_EDIT; SPEC_TOO_LARGE
   *     when the content is over 2 MB in UTF-8, checked before any VCS call; SPEC_CONFLICT when the
   *     file changed since the request's sha was read, with the current sha in {@code currentSha}
   */
  public SaveSpecResponse save(String projectId, SaveSpecRequest request) {
    Project project = findProject(projectId);
    Membership member = requireMember(project, Permission.SPEC_EDIT);
    if (request.content().getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
      throw new AppException(ErrorCode.SPEC_TOO_LARGE, "spec is larger than 2 MB");
    }
    VcsClient vcs = vcsProvider.forProject(member.userId());
    String repo = project.getRepoFullName();
    String path = project.getSpecPath();
    CommitRef commit;
    try {
      commit =
          vcs.putFile(
              repo, path, request.content(), request.sha(), commitMessage(request.message()));
    } catch (VcsConflictException e) {
      String currentSha = vcs.getFile(repo, path, project.getDefaultBranch()).sha();
      throw new AppException(
          ErrorCode.SPEC_CONFLICT,
          "Someone else changed this file",
          Map.of("currentSha", currentSha),
          e);
    }
    return new SaveSpecResponse(commit.sha(), GitBlob.sha(request.content()), null, null);
  }

  private Project findProject(String projectId) {
    return projects
        .findById(projectId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, PROJECT_NOT_FOUND));
  }

  /**
   * The caller's membership with the permission. A non-member gets the same NOT_FOUND as a missing
   * project, as {@link dev.emitkit.api.access.ProjectAccess} gives, so the 404 does not reveal
   * whether the project exists. ProjectAccess returns only the project, and the save needs the
   * caller's user id, hence this variant.
   */
  private Membership requireMember(Project project, Permission permission) {
    try {
      return orgAccess.require(project.getOrgId(), permission);
    } catch (AppException e) {
      if (e.code() == ErrorCode.NOT_FOUND) {
        throw new AppException(ErrorCode.NOT_FOUND, PROJECT_NOT_FOUND);
      }
      throw e;
    }
  }

  private static FileContent readSpec(VcsClient vcs, Project project) {
    try {
      return vcs.getFile(
          project.getRepoFullName(), project.getSpecPath(), project.getDefaultBranch());
    } catch (VcsNotFoundException e) {
      throw new AppException(
          ErrorCode.SPEC_NOT_FOUND, "spec file not found: " + project.getSpecPath(), Map.of(), e);
    }
  }

  private static String formatOf(String specPath) {
    return specPath.endsWith(".json") ? "json" : "yaml";
  }

  private static String commitMessage(@Nullable String message) {
    return message == null || message.isBlank() ? DEFAULT_MESSAGE : message;
  }
}
