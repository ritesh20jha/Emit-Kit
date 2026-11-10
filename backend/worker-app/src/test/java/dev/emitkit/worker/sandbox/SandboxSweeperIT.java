package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import dev.emitkit.common.id.Ids;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Real-Docker test for {@link SandboxSweeper}, run on the daemon named by {@code
 * sandbox.docker-host}. Every container it creates carries a unique {@code emitkit.test} label, so
 * assertions ignore the rest of the daemon. The sweep itself matches {@code emitkit.sandbox=true}
 * on the whole daemon, so no other sandbox work may run while this test does.
 */
class SandboxSweeperIT {

  private static final String DOCKER_HOST =
      System.getProperty("sandbox.docker-host", "unix:///var/run/docker.sock");
  private static final String IMAGE = "alpine:3.20";
  private static final String TEST_LABEL = "emitkit.test";
  private static final String TEST_ID = Ids.newUlid();

  private static DockerClient docker;

  @BeforeAll
  static void connect() throws InterruptedException {
    docker = SandboxConfig.newDockerClient(DOCKER_HOST);
    docker.pullImageCmd("alpine").withTag("3.20").start().awaitCompletion();
  }

  @AfterAll
  static void close() throws IOException {
    docker.close();
  }

  @AfterEach
  void removeTestContainers() {
    for (Container c : testContainers()) {
      docker.removeContainerCmd(c.getId()).withForce(true).withRemoveVolumes(true).exec();
    }
  }

  @Test
  void sweeperRemovesOldLabelledContainers() throws InterruptedException {
    String old = create(true);
    // Docker reports creation in whole seconds. A 1.1 s gap puts the young container at least one
    // full second after the old one, so one sweep can separate them.
    Thread.sleep(1100);
    String young = create(true);
    String unlabelled = create(false);
    Instant oldCreated = Instant.ofEpochSecond(createdAt(old));

    new SandboxSweeper(docker).sweep(Instant.now());
    assertThat(testIds()).containsExactlyInAnyOrder(old, young, unlabelled);

    // The cutoff is 10 minutes before this instant, which is 0.5 s after the old container's
    // creation second. The old container is past it; the young one is not.
    new SandboxSweeper(docker).sweep(oldCreated.plus(Duration.ofMinutes(10)).plusMillis(500));
    assertThat(testIds()).containsExactlyInAnyOrder(young, unlabelled);
  }

  /** Creates a stopped container, labelled {@code emitkit.sandbox=true} when asked. */
  private static String create(boolean sandboxLabelled) {
    Map<String, String> labels =
        sandboxLabelled
            ? Map.of(DockerSandboxRunner.LABEL_SANDBOX, "true", TEST_LABEL, TEST_ID)
            : Map.of(TEST_LABEL, TEST_ID);
    return docker.createContainerCmd(IMAGE).withLabels(labels).withCmd("true").exec().getId();
  }

  private static long createdAt(String id) {
    return testContainers().stream()
        .filter(c -> c.getId().equals(id))
        .findFirst()
        .orElseThrow()
        .getCreated();
  }

  private static List<String> testIds() {
    return testContainers().stream().map(Container::getId).toList();
  }

  private static List<Container> testContainers() {
    return docker
        .listContainersCmd()
        .withShowAll(true)
        .withLabelFilter(Map.of(TEST_LABEL, TEST_ID))
        .exec();
  }
}
