package dev.emitkit.api.runs;

import dev.emitkit.api.common.Page;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.spec.lint.LintIssue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Runs: list and start them per project, then read, cancel, rerun or decide one. Bodies are stubs.
 */
@RestController
@Tag(name = "Runs", description = "Generation runs: start, read, cancel, rerun and approve them.")
public class RunController {

  @GetMapping("/api/v1/projects/{id}/runs")
  @Operation(summary = "List the runs of a project, newest first, one page at a time")
  public Page<RunSummary> listRuns(
      @PathVariable("id") String id,
      @RequestParam(name = "cursor", required = false) @Nullable String cursor,
      @RequestParam(name = "limit", required = false) @Nullable Integer limit,
      @Parameter(description = "Only runs with this status")
          @RequestParam(name = "status", required = false)
          @Nullable RunStatus status,
      @Parameter(description = "Only runs started by this trigger")
          @RequestParam(name = "trigger", required = false)
          @Nullable RunTrigger trigger) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/projects/{id}/runs")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Start a generation run for a project")
  public RunDetail startRun(
      @PathVariable("id") String id, @Valid @RequestBody TriggerRunRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/api/v1/runs/{runId}")
  @Operation(summary = "Read one run")
  public RunDetail getRun(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/runs/{runId}/cancel")
  @Operation(summary = "Cancel a run that has not finished")
  public RunDetail cancelRun(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/runs/{runId}/rerun")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Start a new run with the same spec as this one")
  public RunDetail rerunRun(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @PostMapping("/api/v1/runs/{runId}/approval")
  @Operation(summary = "Approve or reject a run that is awaiting approval")
  public RunDetail decideApproval(
      @PathVariable("runId") String runId, @Valid @RequestBody ApprovalRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/api/v1/runs/{runId}/lint")
  @Operation(summary = "List the lint issues found in the spec of a run")
  public List<LintIssue> listRunLint(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
