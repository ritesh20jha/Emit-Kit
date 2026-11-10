package dev.emitkit.worker.sandbox;

import com.github.dockerjava.api.DockerClient;
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
 * <p>Input and output travel through an anonymous volume at {@code /io}, because {@code docker cp}
 * cannot write into a tmpfs or a read-only rootfs. The wrapper copies {@code in.tar} into {@code
 * /work}, runs the check, and copies {@code out.tar} and {@code report.json} back.
 *
 * <p><b>Known risk:</b> {@code /io/out} is a writable anonymous volume on the daemon's disk, not a
 * tmpfs. The {@code /work} size, memory and pids limits therefore do not bound what untrusted code
 * can write there. The risk is bounded by the run timeout, and the volume is removed with the
 * container. The mitigation belongs to deployment (OPS-01): put the sandbox daemon's data-root on
 * its own size-bounded filesystem.
 */
public class DockerSandboxRunner implements SandboxRunner {

  private static final String IO = "/io";
  private static final String OUT_TAR = "out/out.tar";
  private static final String REPORT = "out/report.json";
  private static final String WRAPPER =
      "cp /io/in.tar /work/in.tar && %s; rc=$?; "
          + "cp /work/out.tar /work/report.json /io/out/ 2>/dev/null; exit $rc";
  private static final Duration POLL = Duration.ofMillis(500);
  private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

  private final DockerClient docker;
  private final SandboxProperties properties;
  private final Semaphore permits;

  /** Creates a runner. {@code maxConcurrent} must be at least 1. */
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

  private void acquirePermit(CancellationToken token) {
    try {
      do {
        throwIfCancelled(token);
      } while (!permits.tryAcquire(POLL.toMillis(), TimeUnit.MILLISECONDS));
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
    Path archive = null;
    try {
      archive = Files.createTempFile("sandbox-in-", ".tar");
      writeInputArchive(req.inputTar(), archive);
      containerId = create(image);
      try (InputStream in = Files.newInputStream(archive)) {
        var copy = docker.copyArchiveToContainerCmd(containerId);
        copy.withRemotePath(IO).withTarInputStream(in).exec();
      }
      docker.startContainerCmd(containerId).exec();
      if (awaitExit(containerId, timeout, token)) {
        return new SandboxResult(null, null, true);
      }
      throwIfCancelled(token);
      var copyOut = docker.copyArchiveFromContainerCmd(containerId, IO + "/out");
      try (InputStream out = copyOut.exec()) {
        Extracted outputs = extractOutputs(out, OutputLimits.DEFAULT);
        return new SandboxResult(outputs.outputTar(), outputs.reportJson(), false);
      }
    } catch (CancellationException e) {
      throw e;
    } catch (IOException | DockerException | DockerClientException e) {
      throw infra(e);
    } catch (RuntimeException e) {
      // The Docker transport wraps socket failures in a plain RuntimeException. Anything else is a
      // programming error and must not be retried as transient.
      if (e.getCause() instanceof IOException) {
        throw infra(e);
      }
      throw e;
    } finally {
      // Cleanup runs with the interrupt flag cleared, so Docker calls are not aborted.
      boolean interrupted = Thread.interrupted();
      try {
        if (containerId != null) {
          remove(containerId);
        }
        deleteQuietly(archive);
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private static SandboxInfraException infra(Exception cause) {
    return new SandboxInfraException("sandbox run failed on the Docker daemon: " + cause, cause);
  }

  /** Waits for the container to stop. Returns true on timeout. Kills it on timeout or cancel. */
  private boolean awaitExit(String containerId, Duration timeout, CancellationToken token) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (Boolean.TRUE.equals(
          docker.inspectContainerCmd(containerId).exec().getState().getRunning())) {
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
      // Kill while the flag is clear, then report the interrupt to the caller.
      kill(containerId);
      Thread.currentThread().interrupt();
      throw new CancellationException("interrupted while the sandbox container ran");
    }
  }

  private void kill(String containerId) {
    try {
      docker.killContainerCmd(containerId).exec();
    } catch (RuntimeException e) {
      // Usually the container already stopped; the forced removal in finally still cleans up.
      log.warn("could not kill sandbox container {}: {}", containerId, e.getMessage());
    }
  }

  private String create(String image) {
    return docker
        .createContainerCmd(image)
        .withUser("10001")
        .withLabels(Map.of("emitkit.sandbox", "true", "emitkit.run", Ids.newUlid()))
        .withEntrypoint("/bin/sh", "-c", String.format(WRAPPER, properties.checkCommand()))
        .withVolumes(new Volume(IO))
        .withHostConfig(
            HostConfig.newHostConfig()
                .withRuntime(properties.runtime())
                .withNetworkMode("none")
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/work", "rw,size=512m,uid=10001"))
                .withNanoCPUs(1_000_000_000L)
                .withMemory(1L << 30)
                .withMemorySwap(1L << 30)
                .withPidsLimit(256L)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges")))
        .exec()
        .getId();
  }

  /** Writes {@code in.tar} and an {@code out/} directory that the check user can write to. */
  private static void writeInputArchive(Path inputTar, Path archive) throws IOException {
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
      TarArchiveEntry input = new TarArchiveEntry("in.tar");
      input.setSize(Files.size(inputTar));
      input.setMode(0644);
      tar.putArchiveEntry(input);
      Files.copy(inputTar, tar);
      tar.closeArchiveEntry();
      // The volume root is root-owned 0755; only this 0777 directory is writable by uid 10001.
      TarArchiveEntry out = new TarArchiveEntry("out/");
      out.setMode(0777);
      tar.putArchiveEntry(out);
      tar.closeArchiveEntry();
    }
  }

  /** Size and count bounds on the untrusted output archive. */
  record OutputLimits(int maxReportBytes, long maxOutputTarBytes, int maxEntries) {

    static final OutputLimits DEFAULT = new OutputLimits(4 << 20, 600L << 20, 16);
  }

  /** Outputs read from the archive. A null field means absent or rejected by a limit. */
  record Extracted(Path outputTar, String reportJson) {}

  /**
   * Reads {@code out/out.tar} into a new temp file and {@code out/report.json} into a string. Other
   * entries are ignored. An output that is over its limit or duplicated is missing. More entries
   * than the limit discards both. The caller owns the returned temp file.
   */
  static Extracted extractOutputs(InputStream archive, OutputLimits limits) throws IOException {
    Path outputTar = null;
    String report = null;
    Set<String> seen = new HashSet<>();
    boolean ok = false;
    try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
      int entries = 0;
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (++entries > limits.maxEntries()) {
          log.warn("sandbox output has over {} entries; discarding all", limits.maxEntries());
          return new Extracted(null, null);
        }
        String name = entry.getName();
        if (!(OUT_TAR.equals(name) || REPORT.equals(name)) || !isRegularFile(entry)) {
          continue;
        }
        if (!seen.add(name)) {
          log.warn("sandbox output {} is duplicated; treating it as missing", name);
          if (OUT_TAR.equals(name)) {
            deleteQuietly(outputTar);
            outputTar = null;
          } else {
            report = null;
          }
        } else if (OUT_TAR.equals(name)) {
          outputTar = Files.createTempFile("sandbox-out-", ".tar");
          if (!copyBounded(tar, outputTar, limits.maxOutputTarBytes())) {
            log.warn("sandbox out.tar is over the size limit; treating it as missing");
            deleteQuietly(outputTar);
            outputTar = null;
          }
        } else {
          byte[] bytes = tar.readNBytes(limits.maxReportBytes() + 1);
          if (bytes.length > limits.maxReportBytes()) {
            log.warn("sandbox report.json is over the size limit; treating it as missing");
          } else {
            report = new String(bytes, StandardCharsets.UTF_8);
          }
        }
      }
      ok = true;
      return new Extracted(outputTar, report);
    } finally {
      if (!ok) {
        deleteQuietly(outputTar);
      }
    }
  }

  /** True only for a regular file; {@code isFile()} alone also accepts symlinks and FIFOs. */
  private static boolean isRegularFile(TarArchiveEntry e) {
    return e.isFile()
        && !(e.isDirectory()
            || e.isSymbolicLink()
            || e.isLink()
            || e.isCharacterDevice()
            || e.isBlockDevice()
            || e.isFIFO());
  }

  /** Copies the current tar entry to {@code target}. Returns false if it exceeds {@code max}. */
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
