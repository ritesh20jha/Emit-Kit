package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the sandbox beans alone, with no RabbitMQ and no image pulls. The daemon socket does not
 * exist, so the runc path must not touch it.
 */
class SandboxContextTest {

  @Test
  void runcUnderTestProfile_startsWithSandboxBeans() {
    try (ConfigurableApplicationContext ctx = app("runc", "test").run()) {
      assertThat(ctx.getBeansOfType(SandboxRunner.class)).hasSize(1);
      assertThat(ctx.getBeansOfType(SandboxStartupCheck.class)).hasSize(1);
      assertThat(ctx.getBeansOfType(SandboxSweeper.class)).hasSize(1);
    }
  }

  @Test
  void runscWithoutDaemonSocket_failsBoot() {
    assertThatThrownBy(() -> app("runsc").run()).hasMessageContaining("cannot read docker info");
  }

  private static SpringApplication app(String runtime, String... profiles) {
    SpringApplication app = new SpringApplication(SandboxConfig.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    app.setDefaultProperties(
        Map.of(
            "emitkit.sandbox.docker-host",
            "unix:///nonexistent-emitkit/docker.sock",
            "emitkit.sandbox.runtime",
            runtime));
    app.setAdditionalProfiles(profiles);
    return app;
  }
}
