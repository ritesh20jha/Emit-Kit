package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
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

  /** The default runtime is runc (spec 5.6), and prod accepts it without reaching the daemon. */
  @Test
  void defaultRuntimeUnderProdProfile_isRuncAndStarts() {
    try (ConfigurableApplicationContext ctx = app(null, "prod").run()) {
      assertThat(ctx.getBean(SandboxProperties.class).runtime()).isEqualTo("runc");
      assertThat(ctx.getBeansOfType(SandboxStartupCheck.class)).hasSize(1);
    }
  }

  @Test
  void runscWithoutDaemonSocket_failsBoot() {
    assertThatThrownBy(() -> app("runsc").run()).hasMessageContaining("cannot read docker info");
  }

  /** A null {@code runtime} leaves emitkit.sandbox.runtime unset, so its default applies. */
  private static SpringApplication app(String runtime, String... profiles) {
    SpringApplication app = new SpringApplication(SandboxConfig.class);
    app.setWebApplicationType(WebApplicationType.NONE);
    Map<String, Object> props = new HashMap<>();
    props.put("emitkit.sandbox.docker-host", "unix:///nonexistent-emitkit/docker.sock");
    if (runtime != null) {
      props.put("emitkit.sandbox.runtime", runtime);
    }
    app.setDefaultProperties(props);
    app.setAdditionalProfiles(profiles);
    return app;
  }
}
