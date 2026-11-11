package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.spec.diff.SpecDiff;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The API spec of a project: read, save, its snapshots and diffs. Bodies are stubs for now. */
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

  @GetMapping("/snapshots")
  @Operation(summary = "List the saved versions of the spec, newest first")
  public List<SnapshotSummary> listSnapshots(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/diff")
  @Operation(summary = "Diff two saved versions of the spec")
  public SpecDiff diffSpec(
      @PathVariable("id") String id,
      @RequestParam(name = "from") String from,
      @RequestParam(name = "to") String to) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
