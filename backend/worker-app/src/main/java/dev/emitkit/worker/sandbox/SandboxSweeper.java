package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Removes sandbox containers that outlived their run, for example after a worker crash (spec 5.6).
 * It matches the {@code emitkit.sandbox=true} label on the whole daemon, so the sandbox daemon must
 * hold only sandbox containers.
 */
class SandboxSweeper {

  static final Duration MAX_AGE = Duration.ofMinutes(10);

  /** The longest run the sweeper leaves alone: a run under this cannot outlive {@link #MAX_AGE}. */
  static final Duration MAX_RUN = MAX_AGE.minus(Duration.ofMinutes(1));

  private static final Logger log = LoggerFactory.getLogger(SandboxSweeper.class);

  private final DockerClient docker;

  SandboxSweeper(DockerClient docker) {
    this.docker = Objects.requireNonNull(docker, "docker");
  }

  @Scheduled(fixedDelay = 10, timeUnit = TimeUnit.MINUTES)
  void sweepScheduled() {
    sweep(Instant.now());
  }

  /** Force-removes labelled containers created before {@code now} minus {@link #MAX_AGE}. */
  void sweep(Instant now) {
    Instant cutoff = now.minus(MAX_AGE);
    List<Container> labelled =
        docker
            .listContainersCmd()
            .withShowAll(true)
            .withLabelFilter(Map.of(DockerSandboxRunner.LABEL_SANDBOX, "true"))
            .exec();
    for (Container c : labelled) {
      if (!Instant.ofEpochSecond(c.getCreated()).isBefore(cutoff)) {
        continue;
      }
      try {
        docker.removeContainerCmd(c.getId()).withForce(true).withRemoveVolumes(true).exec();
        log.info("removed stale sandbox container {}", c.getId());
      } catch (RuntimeException e) {
        log.warn("could not remove stale sandbox container {}; next sweep retries", c.getId(), e);
      }
    }
  }
}
