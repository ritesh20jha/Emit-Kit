package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import dev.emitkit.common.id.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs each check in its own container on the sandbox Docker daemon (spec 5.6).
 *
 * <p>The container gets no network, a read-only root filesystem with a 512 MB {@code /work} tmpfs,
 * a non-root user, and the limits in {@link #hostConfig()}. Input and output go through an
 * anonymous volume at {@code /io}, because {@code docker cp} cannot write into a tmpfs or a
 * read-only rootfs. The runner writes {@code in.tar} and an {@code out/} directory into {@code /io}
 * before start. The container's wrapper copies {@code in.tar} to {@code /work}, runs the check
 * command, and copies {@code out.tar} and {@code report.json} back to {@code /io/out}.
 *
 * <p>Concurrency is limited by a fair semaphore of {@code emitkit.sandbox.max-concurrent} permits.
 * The container is always removed, with its volume, before {@link #run} returns or throws. The
 * Docker client's transport reports daemon failures as plain {@link RuntimeException}s, so every
 * runtime failure of a Docker call is mapped to {@link SandboxInfraException}.
 */
public class DockerSandboxRunner implements SandboxRunner {

  static final String LABEL_SANDBOX = "emitkit.sandbox";
  static final String LABEL_RUN = "emitkit.run";

  private static final String IO_DIR = "/io";
  private static final String OUT_DIR = "out";
  private static final String USER = "10001";
  private static final Duration POLL = Duration.ofMillis(500);
  private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

  private final DockerClient docker;
  private final SandboxProperties properties;
  private final Semaphore permits;

  /**
   * Creates a runner.
   *
   * @param docker client for the sandbox daemon
   * @param properties sandbox settings; {@code maxConcurrent} must be at least 1
   */
  public DockerSandboxRunner(DockerClient docker, SandboxProperties properties) {
    this.docker = Objects.requireNonNull(docker, "docker");
    this.properties = Objects.requireNonNull(properties, "properties");
    if (properties.maxConcurrent() < 1) {
      throw new IllegalArgumentException("emitkit.sandbox.max-concurrent must be at least 1");
    }
    this.permits = new Semaphore(properties.maxConcurrent(), true);
  }

  @Override
  public SandboxResult run(SandboxRequest req, CancellationToken token) {
    acquirePermit(token);
    try {
      return execute(req, token);
    } finally {
      permits.release();
    }
  }

  /** Waits for a permit, checking the token every {@link #POLL}. */
  private void acquirePermit(CancellationToken token) {
    throwIfCancelled(token);
    try {
      while (!permits.tryAcquire(POLL.toMillis(), TimeUnit.MILLISECONDS)) {
        throwIfCancelled(token);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while waiting for a sandbox permit");
    }
  }

  private SandboxResult execute(SandboxRequest req, CancellationToken token) {
    throwIfCancelled(token);
    String image = req.image() != null ? req.image() : properties.image();
    Duration timeout = req.timeout() != null ? req.timeout() : properties.timeout();
    String containerId = null;
    Path inputArchive = null;
    try {
      inputArchive = Files.createTempFile("sandbox-in-", ".tar");
      writeInputArchive(req.inputTar(), inputArchive);
      containerId = create(image);
      copyIn(containerId, inputArchive);
      docker.startContainerCmd(containerId).exec();
      if (awaitExit(containerId, timeout, token)) {
        return new SandboxResult(null, null, true);
      }
      throwIfCancelled(token);
      return collectOutputs(containerId);
    } catch (CancellationException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw new SandboxInfraException(
          "sandbox run failed on the Docker daemon: " + e.getMessage(), e);
    } finally {
      if (containerId != null) {
        remove(containerId);
      }
      deleteQuietly(inputArchive);
    }
  }

  /**
   * Waits until the container stops. Returns true if the timeout passed first; the container is
   * killed in that case. Throws after killing the container if the token is cancelled.
   */
  private boolean awaitExit(String containerId, Duration timeout, CancellationToken token) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (isRunning(containerId)) {
        if (token.isCancelled()) {
          kill(containerId);
          throw new CancellationException("sandbox run cancelled");
        }
        if (System.nanoTime() >= deadline) {
          kill(containerId);
          return true;
        }
        Thread.sleep(POLL.toMillis());
      }
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      kill(containerId);
      throw new CancellationException("interrupted while the sandbox container ran");
    }
  }

  private boolean isRunning(String containerId) {
    InspectContainerResponse.ContainerState state =
        docker.inspectContainerCmd(containerId).exec().getState();
    return Boolean.TRUE.equals(state.getRunning());
  }

  private void kill(String containerId) {
    try {
      docker.killContainerCmd(containerId).exec();
    } catch (RuntimeException e) {
      // Usually the container already stopped. The forced removal in finally still cleans it up.
      log.warn("could not kill sandbox container {}: {}", containerId, e.getMessage());
    }
  }

  private String create(String image) {
    CreateContainerResponse created =
        docker
            .createContainerCmd(image)
            .withUser(USER)
            .withLabels(Map.of(LABEL_SANDBOX, "true", LABEL_RUN, Ids.newUlid()))
            .withEntrypoint("/bin/sh", "-c", wrapperScript())
            .withVolumes(new Volume(IO_DIR))
            .withHostConfig(hostConfig())
            .exec();
    return created.getId();
  }

  private HostConfig hostConfig() {
    return HostConfig.newHostConfig()
        .withRuntime(properties.runtime())
        .withNetworkMode("none")
        .withReadonlyRootfs(true)
        .withTmpFs(Map.of("/work", "rw,size=512m,uid=" + USER))
        .withNanoCPUs(1_000_000_000L)
        .withMemory(1L << 30)
        .withMemorySwap(1L << 30)
        .withPidsLimit(256L)
        .withCapDrop(Capability.ALL)
        .withSecurityOpts(List.of("no-new-privileges"));
  }

  /**
   * Runs inside the container. Copies the input into {@code /work}, runs the check, then copies
   * whatever outputs exist to {@code /io/out}. The exit code of the check is kept.
   */
  private String wrapperScript() {
    return "cp "
        + IO_DIR
        + "/in.tar /work/in.tar && "
        + properties.checkCommand()
        + "; rc=$?; cp /work/out.tar /work/report.json "
        + IO_DIR
        + "/"
        + OUT_DIR
        + "/ 2>/dev/null; exit $rc";
  }

  /** Writes {@code in.tar} (the request input) and an empty {@code out/} directory. */
  private static void writeInputArchive(Path inputTar, Path archive) throws IOException {
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      TarArchiveEntry input = new TarArchiveEntry("in.tar");
      input.setSize(Files.size(inputTar));
      input.setMode(0644);
      tar.putArchiveEntry(input);
      Files.copy(inputTar, tar);
      tar.closeArchiveEntry();

      // The volume root is root-owned 0755, so the non-root check user can write only in this dir.
      TarArchiveEntry out = new TarArchiveEntry(OUT_DIR + "/");
      out.setMode(0777);
      tar.putArchiveEntry(out);
      tar.closeArchiveEntry();
    }
  }

  private void copyIn(String containerId, Path archive) throws IOException {
    try (InputStream in = Files.newInputStream(archive)) {
      docker
          .copyArchiveToContainerCmd(containerId)
          .withRemotePath(IO_DIR)
          .withTarInputStream(in)
          .exec();
    }
  }

  /** Reads {@code out/out.tar} into a new temp file and {@code out/report.json} into a string. */
  private SandboxResult collectOutputs(String containerId) throws IOException {
    Path outputTar = null;
    try (InputStream archive =
            docker.copyArchiveFromContainerCmd(containerId, IO_DIR + "/" + OUT_DIR).exec();
        TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
      String report = null;
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (entry.isDirectory()) {
          continue;
        }
        String name = Path.of(entry.getName()).getFileName().toString();
        if (name.equals("out.tar")) {
          outputTar = Files.createTempFile("sandbox-out-", ".tar");
          Files.copy(tar, outputTar, StandardCopyOption.REPLACE_EXISTING);
        } else if (name.equals("report.json")) {
          report = new String(tar.readAllBytes(), StandardCharsets.UTF_8);
        }
      }
      return new SandboxResult(outputTar, report, false);
    } catch (IOException | RuntimeException e) {
      deleteQuietly(outputTar);
      throw e;
    }
  }

  private void remove(String containerId) {
    try {
      docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
    } catch (RuntimeException e) {
      log.error("could not remove sandbox container {}; the sweeper will retry", containerId, e);
    }
  }

  private static void throwIfCancelled(CancellationToken token) {
    if (token.isCancelled()) {
      throw new CancellationException("sandbox run cancelled");
    }
  }

  private static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      log.warn("could not delete temp file {}", file, e);
    }
  }
}
