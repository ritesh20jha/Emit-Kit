package dev.emitkit.api.runs;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.InputStream;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** The files a run generated, and the zipped artifact of the run. */
@RestController
@Tag(name = "Run files", description = "The files a run generated, and its artifact.")
public class RunFilesController {

  private final RunFilesService files;

  public RunFilesController(RunFilesService files) {
    this.files = files;
  }

  @GetMapping("/api/v1/runs/{runId}/files")
  @Operation(summary = "List the file manifest of a run")
  public List<ManifestEntry> listFiles(@PathVariable("runId") String runId) {
    return files.listFiles(runId);
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
    return files.file(runId, path, diff);
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
  public ResponseEntity<StreamingResponseBody> getArtifact(@PathVariable("runId") String runId) {
    // Opened before the body is returned, so a missing artifact is a problem response.
    InputStream zip = files.download(runId);
    StreamingResponseBody body =
        out -> {
          try (zip) {
            zip.transferTo(out);
          }
        };
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(
            HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"emitkit-" + runId + ".zip\"")
        .body(body);
  }
}
