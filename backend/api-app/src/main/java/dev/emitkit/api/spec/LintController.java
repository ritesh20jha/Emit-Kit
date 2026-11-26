package dev.emitkit.api.spec;

import dev.emitkit.api.auth.CurrentUser;
import dev.emitkit.api.auth.EmitKitUser;
import dev.emitkit.api.projects.LintRequest;
import dev.emitkit.spec.lint.LintIssue;
import dev.emitkit.spec.lint.SpecLinter;
import dev.emitkit.spec.parse.SpecParser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lints a spec without saving it. A parse failure is an issue in the list, not an error. Each
 * signed-in user is limited to 30 requests a minute.
 */
@RestController
@RequestMapping("/api/v1/lint")
@Tag(name = "Lint", description = "Lint a spec without saving it.")
public class LintController {

  private static final SpecParser PARSER = new SpecParser();
  private static final SpecLinter LINTER = new SpecLinter();

  private final LintRateLimiter rateLimiter;

  public LintController(LintRateLimiter rateLimiter) {
    this.rateLimiter = rateLimiter;
  }

  @PostMapping
  @Operation(summary = "Lint a spec and return its issues")
  public List<LintIssue> lint(
      @CurrentUser EmitKitUser user, @Valid @RequestBody LintRequest request) {
    rateLimiter.acquire(user.userId());
    return LINTER.lint(PARSER.parse(request.content()), request.content());
  }
}
