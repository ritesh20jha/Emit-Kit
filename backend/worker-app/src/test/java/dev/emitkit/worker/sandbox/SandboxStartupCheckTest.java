package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  void runc_noProfile_failsStartup() {
    var check = check("runc", Set.of("runc"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @Test
  void runc_withProdAndOtherProfile_failsStartup() {
    var check = check("runc", Set.of("runc"), "prod", "foo");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @Test
  void runc_devAndProd_failsStartup() {
    var check = check("runc", Set.of("runc"), "dev", "prod");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @Test
  void runc_devAndStaging_failsStartup() {
    var check = check("runc", Set.of("runc"), "dev", "staging");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("runc is only allowed in dev/test");
  }

  @ParameterizedTest
  @ValueSource(strings = {"kata", "RUNSC", "runsc "})
  void unknownRuntime_failsStartup(String runtime) {
    var check = check(runtime, Set.of("runc", "runsc"), "dev");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.runtime must be runsc or runc")
        .hasMessageContaining("got '" + runtime + "'");
  }

  @ParameterizedTest
  @ValueSource(strings = {"", " "})
  void blankRuntime_failsStartup(String runtime) {
    var check = check(runtime, Set.of("runc", "runsc"), "dev");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.runtime must be runsc or runc");
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
  void testWithRunc_starts() {
    var check = check("runc", Set.of(), "test");

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  @Test
  void runscPresentInDocker_starts() {
    var check = check("runsc", Set.of("runc", "runsc"), "prod");

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  @Test
  void runscMatchIsExact_notPrefix() {
    // A daemon listing "runsc-shim" does not provide the runsc runtime.
    var check = check("runsc", Set.of("runc", "runsc-shim"), "prod");

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("does not list a runsc runtime");
  }

  @Test
  void timeoutAtSweeperBound_failsStartup() {
    var check =
        new SandboxStartupCheck(
            () -> Set.of("runsc"), properties("runsc", Duration.ofMinutes(9)), environment("prod"));

    assertThatThrownBy(check::check)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.sandbox.timeout must be under");
  }

  @Test
  void timeoutJustBelowSweeperBound_starts() {
    var check =
        new SandboxStartupCheck(
            () -> Set.of("runsc"),
            properties("runsc", Duration.ofMinutes(9).minusSeconds(1)),
            environment("prod"));

    assertThatCode(check::check).doesNotThrowAnyException();
  }

  private static SandboxStartupCheck check(
      String runtime, Set<String> daemonRuntimes, String... profiles) {
    return new SandboxStartupCheck(
        () -> daemonRuntimes, properties(runtime), environment(profiles));
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

  private static MockEnvironment environment(String... profiles) {
    var env = new MockEnvironment();
    env.setActiveProfiles(profiles);
    return env;
  }
}
