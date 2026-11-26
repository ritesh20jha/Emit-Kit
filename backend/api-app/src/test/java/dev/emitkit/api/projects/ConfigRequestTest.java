package dev.emitkit.api.projects;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.domain.BreakingGate;
import dev.emitkit.domain.LintFailOn;
import dev.emitkit.domain.OutputKind;
import dev.emitkit.domain.VersionStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigRequestTest {

  @Test
  void toString_neverShowsTheApiKey() {
    ConfigRequest request = request("sk-secret-123");

    assertThat(request.toString()).doesNotContain("sk-secret-123").contains("aiApiKey=[REDACTED]");
  }

  @Test
  void toString_showsNullKeyAsNull() {
    assertThat(request(null).toString()).contains("aiApiKey=null");
  }

  private static ConfigRequest request(String aiApiKey) {
    return new ConfigRequest(
        List.of(OutputKind.SDK),
        List.of("typescript"),
        ".emitkit/",
        null,
        null,
        null,
        null,
        VersionStrategy.EMITKIT_MANAGED,
        BreakingGate.ALLOW,
        LintFailOn.NEVER,
        "gemini",
        null,
        aiApiKey);
  }
}
