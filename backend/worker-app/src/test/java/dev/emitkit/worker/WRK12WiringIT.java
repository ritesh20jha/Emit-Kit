package dev.emitkit.worker;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.worker.pipeline.PipelineRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the worker boots with the pipeline enabled, against the real application class. The
 * production worker never migrates; this test turns Flyway on so the domain migrations create the
 * schema that the production config expects to find already in place.
 */
@SpringBootTest(
    classes = WorkerApplication.class,
    properties = {
      "emitkit.worker.pipeline.enabled=true",
      "emitkit.vault.cipher=local",
      "spring.flyway.enabled=true",
      // With runc the startup check never reads the daemon, so no sandbox daemon is needed.
      "emitkit.sandbox.runtime=runc"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class WRK12WiringIT {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-alpine");

  @Container @ServiceConnection
  static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.4.0-management-alpine");

  @Container
  @ServiceConnection(name = "redis")
  static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:8.10.3-alpine")).withExposedPorts(6379);

  @Autowired private ApplicationContext context;
  @Autowired private PipelineRunner pipelineRunner;
  @Autowired private ArtifactStorage artifactStorage;
  @Autowired private GenerationRunRepository runs;

  @Test
  void contextLoadsWithPipelineEnabled() {
    assertThat(pipelineRunner).isNotNull();
    assertThat(artifactStorage).isNotNull();
    assertThat(runs).isNotNull();
    // RunLock is package-private in the messaging package, so look it up by bean name.
    assertThat(context.getBean("runLock")).isNotNull();
  }
}
