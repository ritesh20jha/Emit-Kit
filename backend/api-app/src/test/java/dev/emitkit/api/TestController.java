package dev.emitkit.api;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints. The error endpoints throw what ProblemDetailsAdvice must map; the /api/v1
 * and /webhooks endpoints let SecurityConfigTest exercise the filter chains.
 */
@RestController
class TestController {

  /** Request body with one required field. */
  record Item(@NotBlank String name) {}

  @GetMapping("/test/conflict")
  String conflict() {
    throw new AppException(
        ErrorCode.SPEC_CONFLICT, "spec changed", Map.of("currentSha", "abc"), null);
  }

  @PostMapping("/test/items")
  String create(@Valid @RequestBody Item item) {
    return item.name();
  }

  @GetMapping("/test/denied")
  String denied() {
    throw new AccessDeniedException("x");
  }

  @GetMapping("/test/boom")
  String boom() {
    throw new RuntimeException("db password=x");
  }

  @GetMapping("/api/v1/test")
  String apiGet() {
    return "ok";
  }

  @PostMapping("/api/v1/test")
  String apiPost() {
    return "ok";
  }

  @GetMapping("/api/v1/test/session")
  String session(HttpSession session) {
    session.setAttribute("probe", "1");
    return "ok";
  }

  @PostMapping("/webhooks/test")
  String webhook() {
    return "ok";
  }
}
