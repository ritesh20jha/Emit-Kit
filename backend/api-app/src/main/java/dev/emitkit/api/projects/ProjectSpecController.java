package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API spec of a project: read and save. Snapshots and diffs are in {@code
 * dev.emitkit.api.spec}. Bodies are stubs for now.
 */
@RestController
@RequestMapping("/api/v1/projects/{id}/spec")
@Tag(name = "Project spec", description = "The API spec of a project, its snapshots and diffs.")
public class ProjectSpecController {

  @GetMapping
  @Operation(summary = "Read the current spec of a project")
  public SpecResponse getSpec(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PutMapping
  @Operation(summary = "Save the spec, optionally starting a generation run")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Saved; runId is set when a generation run was started"),
    @ApiResponse(
        responseCode = "202",
        description = "Saved, but the generation run could not be started; see runError")
  })
  public SaveSpecResponse saveSpec(
      @PathVariable("id") String id, @Valid @RequestBody SaveSpecRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
