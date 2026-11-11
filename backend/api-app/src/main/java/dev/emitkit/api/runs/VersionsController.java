package dev.emitkit.api.runs;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The SDK versions a project has published. The body is a stub for now. */
@RestController
@Tag(name = "Versions", description = "The SDK versions a project has published.")
public class VersionsController {

  @GetMapping("/api/v1/projects/{id}/versions")
  @Operation(summary = "List the published versions of a project, newest first")
  public List<VersionResponse> listVersions(@PathVariable("id") String id) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
