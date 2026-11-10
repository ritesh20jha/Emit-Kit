package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import dev.emitkit.common.id.Ids;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * Docker client's transport reports daemon failures as plain {@link RuntimeException}s, so Docker
 * and I/O failures are mapped to {@link SandboxInfraException} while programming errors are not.
 * Outputs read back from the container are bounded by {@link OutputLimits}; an output over its
 * limit is treated as missing.
 *
 * <p><b>Known risk:</b> {@code /io/out} is a writable anonymous volume on the daemon's disk, not a
 * tmpfs. The {@code /work} size, memory and pids limits therefore do not bound what untrusted code
 * can write there. The risk is bounded by the run timeout, and the volume is removed with the
 * container. The mitigation belongs to deployment (OPS-01): put the sandbox daemon's data-root on
 * its own size-bounded filesystem.
 */
public class DockerSandboxRunner implements SandboxRunner {

  static final String LABEL_SANDBOX = "emitkit.sandbox";
  static final String LABEL_RUN = "emitkit.run";

  private static final String IO_DIR = "/io";
  private static final String OUT_DIR = "out";
  private static final String OUT_TAR_ENTRY = OUT_DIR + "/out.tar";
  private static final String REPORT_ENTRY = OUT_DIR + "/report.json";
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
    Objects.requireNonNull(req, "req");
    Objects.requireNonNull(req.inputTar(), "req.inputTar");
    Objects.requireNonNull(token, "token");
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
    } catch (IOException | DockerException | DockerClientException e) {
      throw infraFailure(e);
    } catch (RuntimeException e) {
      // The Docker transport wraps socket failures in a plain RuntimeException. Other runtime
      // exceptions are programming errors and must not be retried as transient.
      if (e.getCause() instanceof IOException) {
        throw infraFailure(e);
      }
      throw e;
    } finally {
      // Cleanup runs with the interrupt flag cleared, so the Docker calls are not aborted, and the
      // flag is restored afterwards.
      boolean interrupted = Thread.interrupted();
      try {
        if (containerId != null) {
          remove(containerId);
        }
        deleteQuietly(inputArchive);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static SandboxInfraException infraFailure(Exception cause) {
    return new SandboxInfraException(
        "sandbox run failed on the Docker daemon: " + cause.getMessage(), cause);
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
      // Kill first, while the flag is clear; then report the interrupt to the caller.
      kill(containerId);
      Thread.currentThread().interrupt();
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

  private SandboxResult collectOutputs(String containerId) throws IOException {
    try (InputStream archive =
        docker.copyArchiveFromContainerCmd(containerId, IO_DIR + "/" + OUT_DIR).exec()) {
      Extracted outputs = extractOutputs(archive, OutputLimits.DEFAULT);
      return new SandboxResult(outputs.outputTar(), outputs.reportJson(), false);
    }
  }

  /** Size and count bounds on the untrusted output archive. */
  record OutputLimits(int maxReportBytes, long maxOutputTarBytes, int maxEntries) {

    static final OutputLimits DEFAULT = new OutputLimits(4 * 1024 * 1024, 600L * 1024 * 1024, 16);
  }

  /** The outputs read from the archive. Null means absent or rejected by a limit. */
  record Extracted(Path outputTar, String reportJson) {}

  /**
   * Reads {@code out/out.tar} into a new temp file and {@code out/report.json} into a string.
   *
   * <p>Only the exact top-level regular files are read. A nested or non-file entry is ignored. An
   * output over its limit, or a duplicate of one, is treated as missing and logged as a warning.
   * More entries than {@link OutputLimits#maxEntries()} discards both outputs. The caller owns the
   * returned temp file. A failure deletes it.
   */
  static Extracted extractOutputs(InputStream archive, OutputLimits limits) throws IOException {
    Path outputTar = null;
    String report = null;
    Set<String> seen = new HashSet<>();
    Set<String> rejected = new HashSet<>();
    boolean tooManyEntries = false;
    boolean completed = false;
    try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
      int entries = 0;
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (++entries > limits.maxEntries()) {
          tooManyEntries = true;
          break;
        }
        String name = entry.getName();
        boolean wanted = OUT_TAR_ENTRY.equals(name) || REPORT_ENTRY.equals(name);
        if (!wanted || !isRegularFile(entry)) {
          continue;
        }
        if (!seen.add(name)) {
          log.warn("sandbox output has a duplicate entry {}; treating it as missing", name);
          rejected.add(name);
          continue;
        }
        if (OUT_TAR_ENTRY.equals(name)) {
          outputTar = Files.createTempFile("sandbox-out-", ".tar");
          if (!copyBounded(tar, outputTar, limits.maxOutputTarBytes())) {
            log.warn(
                "sandbox out.tar exceeds {} bytes; treating it as missing",
                limits.maxOutputTarBytes());
            rejected.add(name);
          }
        } else {
          byte[] bytes = tar.readNBytes(limits.maxReportBytes() + 1);
          if (bytes.length > limits.maxReportBytes()) {
            log.warn(
                "sandbox report.json exceeds {} bytes; treating it as missing",
                limits.maxReportBytes());
            rejected.add(name);
          } else {
            report = new String(bytes, StandardCharsets.UTF_8);
          }
        }
      }
      if (tooManyEntries) {
        log.warn(
            "sandbox output has more than {} entries; discarding all outputs", limits.maxEntries());
        rejected.add(OUT_TAR_ENTRY);
        rejected.add(REPORT_ENTRY);
      }
      if (rejected.contains(OUT_TAR_ENTRY)) {
        deleteQuietly(outputTar);
        outputTar = null;
      }
      if (rejected.contains(REPORT_ENTRY)) {
        report = null;
      }
      completed = true;
      return new Extracted(outputTar, report);
    } finally {
      if (!completed) {
        deleteQuietly(outputTar);
      }
    }
  }

  /**
   * True only for a regular file. {@link TarArchiveEntry#isFile()} also reports true for a symlink
   * whose name does not end in a slash, so the link and device types are excluded explicitly.
   */
  private static boolean isRegularFile(TarArchiveEntry entry) {
    return entry.isFile()
        && !entry.isDirectory()
        && !entry.isSymbolicLink()
        && !entry.isLink()
        && !entry.isCharacterDevice()
        && !entry.isBlockDevice()
        && !entry.isFIFO();
  }

  /**
   * Copies the current tar entry to {@code target}. Returns false, leaving a partial file, if over
   * {@code max}.
   */
  private static boolean copyBounded(InputStream in, Path target, long max) throws IOException {
    try (OutputStream out = Files.newOutputStream(target)) {
      byte[] buffer = new byte[64 * 1024];
      long total = 0;
      for (int n = in.read(buffer); n != -1; n = in.read(buffer)) {
        total += n;
        if (total > max) {
          return false;
        }
        out.write(buffer, 0, n);
      }
      return true;
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
