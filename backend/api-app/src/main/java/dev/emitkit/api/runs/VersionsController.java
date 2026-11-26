package dev.emitkit.api.runs;

import dev.emitkit.api.access.Permission;
import dev.emitkit.api.access.ProjectAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The SDK versions a project has published. */
@RestController
@Tag(name = "Versions", description = "The SDK versions a project has published.")
public class VersionsController {

  private final ProjectAccess projectAccess;
  private final VersionService versions;

  public VersionsController(ProjectAccess projectAccess, VersionService versions) {
    this.projectAccess = projectAccess;
    this.versions = versions;
  }

  @GetMapping("/api/v1/projects/{id}/versions")
  @Operation(summary = "List the published versions of a project, newest first")
  public List<VersionResponse> listVersions(@PathVariable("id") String id) {
    projectAccess.require(id, Permission.RUN_VIEW);
    return versions.list(id);
  }
}
