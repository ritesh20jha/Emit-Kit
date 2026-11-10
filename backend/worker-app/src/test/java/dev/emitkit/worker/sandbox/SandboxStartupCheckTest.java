package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SandboxStartupCheckTest {

  @Test
  void prodWithRuncRuntime_failsStartup() {
    var check = check("runc", Set.of("runc"), "prod");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @Test
  void stagingWithRuncRuntime_failsStartup() {
    var check = check("runc", Set.of("runc"), "staging");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @Test
  void runscMissingFromDocker_failsStartup() {
    var check = check("runsc", Set.of("runc"), "prod");

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
            properties("runsc"),
            environment("prod"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("connection refused");
  }

  @Test
  void devWithRunc_starts() {
    var check = check("runc", Set.of(), "dev");

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  @Test
  void runscPresentInDocker_starts() {
    var check = check("runsc", Set.of("runc", "runsc"), "prod");

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  private static SandboxStartupCheck check(
      String runtime, Set<String> daemonRuntimes, String profile) {
    return new SandboxStartupCheck(() -> daemonRuntimes, properties(runtime), environment(profile));
  }

  private static SandboxProperties properties(String runtime) {
    return new SandboxProperties(
        "unix:///nonexistent-emitkit/docker.sock",
        runtime,
        4,
        Duration.ofSeconds(180),
        "emitkit/toolchain:1",
        "/usr/local/bin/check.sh");
  }

  private static MockEnvironment environment(String profile) {
    var env = new MockEnvironment();
    env.setActiveProfiles(profile);
    return env;
  }
}
