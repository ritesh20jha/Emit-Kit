package dev.emitkit.api.projects;

import dev.emitkit.api.access.Permission;
import dev.emitkit.api.access.ProjectAccess;
import dev.emitkit.api.runs.RunService;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.vcs.CommitRef;
import dev.emitkit.vcs.FileContent;
import dev.emitkit.vcs.GitBlob;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.error.VcsConflictException;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads and saves the API spec file of a project on its default branch. The repository is the only
 * store: no database row changes. A save names the blob sha it replaces, so a save based on an
 * older read is refused with a conflict that carries the current sha. Reads and writes use the
 * project's creator token, as the worker does.
 */
@Service
public class SpecService {

  /** The largest spec accepted, in UTF-8 bytes. */
  static final int MAX_SPEC_BYTES = 2 * 1024 * 1024;

  static final String DEFAULT_MESSAGE = "Update API spec (EmitKit)";

  private static final String RUN_NOT_STARTED = "Could not start generation run";

  private static final Logger LOG = LoggerFactory.getLogger(SpecService.class);

  private final ProjectAccess projectAccess;
  private final VcsProvider vcsProvider;
  private final RunService runs;

  public SpecService(ProjectAccess projectAccess, VcsProvider vcsProvider, RunService runs) {
    this.projectAccess = projectAccess;
    this.vcsProvider = vcsProvider;
    this.runs = runs;
  }

  /**
   * Reads the spec of a project.
   *
   * @throws AppException NOT_FOUND when the project is missing or the user is not a member of its
   *     org; FORBIDDEN when the member's role lacks SPEC_VIEW; SPEC_NOT_FOUND when the spec file is
   *     not on the default branch
   */
  public SpecResponse get(String projectId) {
    Project project = projectAccess.require(projectId, Permission.SPEC_VIEW);
    VcsClient vcs = vcsProvider.forProject(project.getCreatedBy());
    FileContent file = readSpec(vcs, project);
    return new SpecResponse(file.content(), file.sha(), formatOf(project.getSpecPath()));
  }

  /**
   * Saves the spec of a project as one commit on the default branch. With {@code generate}, a
   * manual run is then started on the saved spec. The run's permission is checked before the
   * commit, so a caller who may not start runs gets nothing committed.
   *
   * @throws AppException NOT_FOUND or FORBIDDEN as for {@link #get}, with SPEC_EDIT, and with
   *     RUN_TRIGGER too when {@code generate} is set; SPEC_TOO_LARGE when the content is over 2 MB
   *     in UTF-8, checked before any VCS call; SPEC_CONFLICT when the file changed since the
   *     request's sha was read, with the current sha in {@code currentSha}
   */
  public SaveSpecResponse save(String projectId, SaveSpecRequest request, String userId) {
    Project project = projectAccess.require(projectId, Permission.SPEC_EDIT);
    if (request.generate()) {
      projectAccess.require(projectId, Permission.RUN_TRIGGER);
    }
    if (request.content().getBytes(StandardCharsets.UTF_8).length > MAX_SPEC_BYTES) {
      throw new AppException(ErrorCode.SPEC_TOO_LARGE, "spec is larger than 2 MB");
    }
    VcsClient vcs = vcsProvider.forProject(project.getCreatedBy());
    String repo = project.getRepoFullName();
    String path = project.getSpecPath();
    CommitRef commit;
    try {
      commit =
          vcs.putFile(
              repo,
              path,
              request.content(),
              request.sha(),
              commitMessage(request.message()),
              project.getDefaultBranch());
    } catch (VcsConflictException e) {
      throw saveConflict(vcs, project, request.sha(), e);
    }
    String newSha = GitBlob.sha(request.content());
    if (!request.generate()) {
      return new SaveSpecResponse(commit.sha(), newSha, null, null);
    }
    return startRun(projectId, userId, commit.sha(), newSha);
  }

  /**
   * Starts a manual run on the spec just committed. The commit stands whatever happens here, so a
   * run that cannot start is reported in {@code runError} instead of failing the save.
   */
  private SaveSpecResponse startRun(
      String projectId, String userId, String commitSha, String newSha) {
    try {
      GenerationRun run = runs.create(projectId, RunTrigger.MANUAL, false, userId, null);
      return new SaveSpecResponse(commitSha, newSha, run.getId(), null);
    } catch (AppException e) {
      return new SaveSpecResponse(
          commitSha, newSha, null, new ProblemSummary(e.code().name(), e.getMessage()));
    } catch (RuntimeException e) {
      LOG.error("Could not start generation run for project {}", projectId, e);
      return new SaveSpecResponse(
          commitSha, newSha, null, new ProblemSummary(ErrorCode.INTERNAL.name(), RUN_NOT_STARTED));
    }
  }

  /**
   * The error for a write the provider refused. The file is read again: if its sha still equals the
   * one the save named, nobody changed it, so the provider's own conflict (for example a branch
   * protection rule) is returned unchanged. Otherwise the file changed and the error carries the
   * current sha.
   */
  private static AppException saveConflict(
      VcsClient vcs, Project project, String requestedSha, VcsConflictException refusal) {
    FileContent current = readSpec(vcs, project);
    if (current.sha().equals(requestedSha)) {
      return refusal;
    }
    return new AppException(
        ErrorCode.SPEC_CONFLICT,
        "Someone else changed this file",
        Map.of("currentSha", current.sha()),
        refusal);
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
    return specPath.toLowerCase(Locale.ROOT).endsWith(".json") ? "json" : "yaml";
  }

  private static String commitMessage(@Nullable String message) {
    return message == null || message.isBlank() ? DEFAULT_MESSAGE : message;
  }
}
