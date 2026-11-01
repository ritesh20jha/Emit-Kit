package dev.emitkit.api;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoints that throw the errors that ProblemDetailsAdvice must map. */
@RestController
@RequestMapping("/test")
class TestController {

  /** Request body with one required field. */
  record Item(@NotBlank String name) {}

  @GetMapping("/conflict")
  String conflict() {
    throw new AppException(
        ErrorCode.SPEC_CONFLICT, "spec changed", Map.of("currentSha", "abc"), null);
  }

  @PostMapping("/items")
  String create(@Valid @RequestBody Item item) {
    return item.name();
  }

  @GetMapping("/denied")
  String denied() {
    throw new AccessDeniedException("x");
  }

  @GetMapping("/boom")
  String boom() {
    throw new RuntimeException("db password=x");
  }
}
