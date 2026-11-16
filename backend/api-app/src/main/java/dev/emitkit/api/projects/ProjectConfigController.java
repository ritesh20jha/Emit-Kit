package dev.emitkit.api.projects;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The generation settings of a project: read the latest version, or save a new one. */
@RestController
@RequestMapping("/api/v1/projects/{id}/config")
@Tag(name = "Project config", description = "Generation settings of a project.")
public class ProjectConfigController {

  private final ConfigService configs;

  public ProjectConfigController(ConfigService configs) {
    this.configs = configs;
  }

  @GetMapping
  @Operation(summary = "Read the generation settings of a project")
  public ConfigResponse getConfig(@PathVariable("id") String id) {
    return configs.get(id);
  }

  @PutMapping
  @Operation(summary = "Replace the generation settings of a project")
  public ConfigResponse saveConfig(
      @PathVariable("id") String id, @Valid @RequestBody ConfigRequest request) {
    return configs.save(id, request);
  }
}
