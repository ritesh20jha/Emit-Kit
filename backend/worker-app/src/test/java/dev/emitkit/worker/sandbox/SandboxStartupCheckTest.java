package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SandboxStartupCheckTest {

  /**
   * runc is the default runtime (spec 5.6) and is built into every Docker daemon, so the check
   * accepts it without reading the daemon. The supplier would throw if it were called.
   */
  @Test
  void runc_startsWithoutReadingTheDaemon() {
    var check =
        new SandboxStartupCheck(
            () -> {
              throw new AssertionError("daemon must not be read for runc");
            },
            properties("runc"));

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata", "RUNSC", "runsc "})
  void unknownRuntime_failsStartup(String runtime) {
    var check = check(runtime, Set.of("runc", "runsc"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.runtime must be runsc or runc")
        .hasMessageContaining("got '" + runtime + "'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankRuntime_failsStartup(String runtime) {
    var check = check(runtime, Set.of("runc", "runsc"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.runtime must be runsc or runc");
  }

  @Test
  void runscMissingFromDocker_failsStartup() {
    var check = check("runsc", Set.of("runc"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runsc");
  }

  @Test
  void daemonUnreachable_failsStartup() {
    var check =
        new SandboxStartupCheck(
            () -> {
              throw new IllegalArgumentException("connection refused");
            },
            properties("runsc"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connection refused");
  }

  @Test
  void runscPresentInDocker_starts() {
    var check = check("runsc", Set.of("runc", "runsc"));

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  @Test
  void runscMatchIsExact_notPrefix() {
    // A daemon listing "runsc-shim" does not provide the runsc runtime.
    var check = check("runsc", Set.of("runc", "runsc-shim"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not list a runsc runtime");
  }

  @Test
  void timeoutAtSweeperBound_failsStartup() {
    var check =
        new SandboxStartupCheck(() -> Set.of("runsc"), properties("runsc", Duration.ofMinutes(9)));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.timeout must be under");
  }

  @Test
  void timeoutJustBelowSweeperBound_starts() {
    var check =
        new SandboxStartupCheck(
            () -> Set.of("runsc"), properties("runsc", Duration.ofMinutes(9).minusSeconds(1)));

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  private static SandboxStartupCheck check(String runtime, Set<String> daemonRuntimes) {
    return new SandboxStartupCheck(() -> daemonRuntimes, properties(runtime));
  }

  private static SandboxProperties properties(String runtime) {
    return properties(runtime, Duration.ofSeconds(180));
  }

  private static SandboxProperties properties(String runtime, Duration timeout) {
    return new SandboxProperties(
        "unix:///nonexistent-emitkit/docker.sock",
        runtime,
        4,
        timeout,
        "emitkit/toolchain:1",
        "/usr/local/bin/check.sh");
  }
}
