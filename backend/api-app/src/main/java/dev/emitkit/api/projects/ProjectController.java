package dev.emitkit.api.projects;

import dev.emitkit.api.common.Page;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.RunStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Projects: list, create, read, delete and the push webhook. Only create is implemented so far. */
@RestController
@Tag(name = "Projects", description = "Projects, their repositories and push webhooks.")
public class ProjectController {

  private final ProjectService projects;

  public ProjectController(ProjectService projects) {
    this.projects = projects;
  }

  @GetMapping("/api/v1/orgs/{orgId}/projects")
  @Operation(summary = "List the projects of an organization, one page at a time")
  public Page<ProjectSummary> listProjects(
      @PathVariable("orgId") String orgId,
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", required = false) @Nullable Integer limit,
      @RequestParam(name = "q", required = false) @Nullable String q,
      @Parameter(description = "Only projects whose last run has this status")
          @RequestParam(name = "status", required = false)
          @Nullable RunStatus status) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/orgs/{orgId}/projects")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create a project for an existing or a new repository")
  public ProjectDetail createProject(
      @PathVariable("orgId") String orgId, @Valid @RequestBody CreateProjectRequest request) {
    if (request.mode() == CreateProjectRequest.Mode.NEW) {
      throw new AppException(
          ErrorCode.NOT_IMPLEMENTED, "creating a new repository is not implemented");
    }
    return toDetail(projects.createExisting(orgId, request));
  }

  @GetMapping("/api/v1/projects/{id}")
  @Operation(summary = "Read one project")
  public ProjectDetail getProject(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @DeleteMapping("/api/v1/projects/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Delete a project")
  public void deleteProject(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/projects/{id}/webhook")
  @Operation(summary = "Register the push webhook on the project's repository")
  public WebhookStatus registerWebhook(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  private static ProjectDetail toDetail(Project project) {
    Long webhookId = project.getWebhookId();
    return new ProjectDetail(
        project.getId(),
        project.getOrgId(),
        project.getRepoFullName(),
        project.getSpecPath(),
        project.getDefaultBranch(),
        project.getOutputMode(),
        project.getOutputRepoFullName(),
        new WebhookStatus(webhookId != null, webhookId),
        project.getCreatedAt());
  }
}
