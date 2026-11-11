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
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The log of a run, read whole or streamed live. Bodies are stubs for now. */
@RestController
@Tag(name = "Run logs", description = "The log of a run, read whole or streamed.")
public class RunLogsController {

  @GetMapping("/api/v1/runs/{runId}/logs")
  @Operation(summary = "List the log lines of a run, after the given sequence number")
  public List<LogLine> listLogs(
      @PathVariable("runId") String runId,
      @RequestParam(name = "afterSeq", required = false) @Nullable Integer afterSeq) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }

  @GetMapping(
      value = "/api/v1/runs/{runId}/logs/stream",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  @ApiResponse(
      responseCode = "200",
      description = "The log as server-sent events",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              schema = @Schema(type = "string")))
  @Operation(
      summary = "Stream the log of a run as server-sent events",
      description =
          "Two events. `log` carries one LogLine as JSON for each new line. `done` carries the"
              + " text `[DONE]` once the run has finished and the stream closes. SSE id is the"
              + " log seq; on reconnect the stream resumes after Last-Event-ID.")
  public SseEmitter streamLogs(
      @PathVariable("runId") String runId,
      @Parameter(description = "The seq of the last log line the client received")
          @RequestHeader(name = "Last-Event-ID", required = false)
          @Nullable Integer lastEventId) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
