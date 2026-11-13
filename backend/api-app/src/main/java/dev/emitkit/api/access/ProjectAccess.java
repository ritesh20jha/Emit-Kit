package dev.emitkit.api.access;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import org.springframework.stereotype.Service;

/** Checks a project-scoped permission by way of the project's organization. */
@Service
public class ProjectAccess {

  private final ProjectRepository projects;
  private final OrgAccessService orgAccess;

  public ProjectAccess(ProjectRepository projects, OrgAccessService orgAccess) {
    this.projects = projects;
    this.orgAccess = orgAccess;
  }

  /**
   * Returns the project when the signed-in user may perform {@code p} in the project's org.
   *
   * @throws AppException NOT_FOUND when the project is missing, or as {@link
   *     OrgAccessService#require} for the org check
   */
  public Project require(String projectId, Permission p) {
    Project project =
        projects
            .findById(projectId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "project not found"));
    orgAccess.require(project.getOrgId(), p);
    return project;
  }
}
