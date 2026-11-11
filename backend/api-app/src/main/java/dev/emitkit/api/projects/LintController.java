package dev.emitkit.api.projects;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.spec.lint.LintIssue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lints a spec without saving it. The body is a stub until the lint ticket. */
@RestController
@RequestMapping("/api/v1/lint")
@Tag(name = "Lint", description = "Lint a spec without saving it.")
public class LintController {

  @PostMapping
  @Operation(summary = "Lint a spec and return its issues")
  public List<LintIssue> lint(@Valid @RequestBody LintRequest request) {
    throw new AppException(ErrorCode.NOT_IMPLEMENTED, "not implemented");
  }
}
