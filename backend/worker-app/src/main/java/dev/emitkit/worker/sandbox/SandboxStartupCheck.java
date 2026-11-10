package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.RuntimeInfo;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Stops the worker from starting with a sandbox runtime that is not allowed or not installed (spec
 * 5.6). A failure is an {@link IllegalStateException}, not a {@link SandboxInfraException}: at boot
 * it is fatal, not a transient per-job error.
 */
class SandboxStartupCheck implements ApplicationRunner {

  private final Supplier<Set<String>> daemonRuntimes;
  private final SandboxProperties properties;
  private final Environment environment;

  /** {@code daemonRuntimes} returns the runtime names the sandbox daemon reports. */
  SandboxStartupCheck(
      Supplier<Set<String>> daemonRuntimes, SandboxProperties properties, Environment environment) {
    this.daemonRuntimes = Objects.requireNonNull(daemonRuntimes, "daemonRuntimes");
    this.properties = Objects.requireNonNull(properties, "properties");
    this.environment = Objects.requireNonNull(environment, "environment");
  }

  @Override
  public void run(ApplicationArguments args) {
    check();
  }

  /**
   * Throws {@link IllegalStateException} unless the configured runtime is runsc, or runc under the
   * dev or test profile (spec 5.6). An unknown or blank value fails too: an empty runtime would
   * silently run under the daemon default.
   */
  void check() {
    String runtime = properties.runtime();
    if (!"runsc".equals(runtime) && !"runc".equals(runtime)) {
      throw new IllegalStateException(
          "emitkit.sandbox.runtime must be runsc or runc, got '" + runtime + "'");
    }
    if ("runc".equals(runtime) && !environment.acceptsProfiles(Profiles.of("dev", "test"))) {
      throw new IllegalStateException(
          "emitkit.sandbox.runtime=runc: runc is only allowed in dev/test");
    }
    if ("runsc".equals(runtime) && !daemonRuntimes().contains("runsc")) {
      throw new IllegalStateException(
          "emitkit.sandbox.runtime=runsc but the sandbox daemon at "
              + properties.dockerHost()
              + " does not list a runsc runtime; install gVisor on that daemon");
    }
  }

  private Set<String> daemonRuntimes() {
    try {
      return daemonRuntimes.get();
    } catch (RuntimeException e) {
      throw new IllegalStateException(
          "cannot read docker info from the sandbox daemon at "
              + properties.dockerHost()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  /** The runtime names that {@code docker info} reports for the daemon behind {@code docker}. */
  static Set<String> runtimesOf(DockerClient docker) {
    Map<String, RuntimeInfo> runtimes = docker.infoCmd().exec().getRuntimes();
    return runtimes == null ? Set.of() : runtimes.keySet();
  }
}
