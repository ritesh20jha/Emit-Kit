package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The generation settings of a project. Bodies are stubs until the feature ticket. */
@RestController
@RequestMapping("/api/v1/projects/{id}/config")
@Tag(name = "Project config", description = "Generation settings of a project.")
public class ProjectConfigController {

  @GetMapping
  @Operation(summary = "Read the generation settings of a project")
  public ConfigResponse getConfig(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PutMapping
  @Operation(summary = "Replace the generation settings of a project")
  public ConfigResponse saveConfig(
      @PathVariable("id") String id, @Valid @RequestBody ConfigRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
