package dev.emitkit.api.runs;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The files a run generated, and the zipped artifact of the run. Bodies are stubs for now. */
@RestController
@Tag(name = "Run files", description = "The files a run generated, and its artifact.")
public class RunFilesController {

  @GetMapping("/api/v1/runs/{runId}/files")
  @Operation(summary = "List the file manifest of a run")
  public List<ManifestEntry> listFiles(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping("/api/v1/runs/{runId}/files/{*path}")
  @Operation(summary = "Read one generated file, or its diff against the target branch")
  public FileView getFile(
      @PathVariable("runId") String runId,
      @Parameter(
              description =
                  "Manifest path. Send '/' separators unencoded; percent-encode each segment.")
          @PathVariable("path")
          String path,
      @RequestParam(name = "diff", defaultValue = "false") boolean diff) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping(value = "/api/v1/runs/{runId}/artifact", produces = "application/zip")
  @ApiResponse(
      responseCode = "200",
      description = "The zipped output of the run",
      content =
          @Content(
              mediaType = "application/zip",
              schema = @Schema(type = "string", format = "binary")))
  @Operation(summary = "Download the zipped output of a run")
  public Resource getArtifact(@PathVariable("runId") String runId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
