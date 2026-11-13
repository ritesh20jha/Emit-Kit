package dev.emitkit.api.access;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import org.springframework.stereotype.Service;

/** Checks a project-scoped permission by way of the project's organization. */
@Service
public class ProjectAccess {

  private static final String PROJECT_NOT_FOUND = "project not found";

  private final ProjectRepository projects;
  private final OrgAccessService orgAccess;

  public ProjectAccess(ProjectRepository projects, OrgAccessService orgAccess) {
    this.projects = projects;
    this.orgAccess = orgAccess;
  }

  /**
   * Returns the project when the signed-in user may perform {@code p} in the project's org.
   *
   * <p>A non-member gets the same NOT_FOUND as a missing project, so a 404 does not reveal whether
   * the project exists.
   *
   * @throws AppException NOT_FOUND when the project is missing or the user is not a member of its
   *     org; FORBIDDEN when the member's role lacks {@code p}
   */
  public Project require(String projectId, Permission p) {
    Project project =
        projects
            .findById(projectId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, PROJECT_NOT_FOUND));
    try {
      orgAccess.require(project.getOrgId(), p);
    } catch (AppException e) {
      if (e.code() == ErrorCode.NOT_FOUND) {
        throw new AppException(ErrorCode.NOT_FOUND, PROJECT_NOT_FOUND);
      }
      throw e;
    }
    return project;
  }
}
