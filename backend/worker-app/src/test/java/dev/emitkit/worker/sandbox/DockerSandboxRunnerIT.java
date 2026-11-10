package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import dev.emitkit.common.id.Ids;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Escape suite for {@link DockerSandboxRunner}. Runs real containers on the Docker daemon at {@code
 * -Dsandbox.docker-host} (default {@code unix:///var/run/docker.sock}) with runtime {@code
 * -Dsandbox.runtime} (default {@code runsc}).
 *
 * <p>The test image is alpine plus a stand-in for {@code check.sh}: it untars {@code in.tar} into
 * {@code /work} and runs the file {@code cmd} from it, with stdout and stderr going to {@code
 * report.json}. A test command can therefore write {@code /work/out.tar} itself.
 */
class DockerSandboxRunnerIT {

  private static final String RUNTIME = System.getProperty("sandbox.runtime", "runsc");
  private static final String DOCKER_HOST =
      System.getProperty("sandbox.docker-host", "unix:///var/run/docker.sock");
  private static final String CHECK_COMMAND = "/usr/local/bin/testcheck.sh";
  private static final String IMAGE = "emitkit/sandbox-it:" + Ids.newUlid();
  private static final String LABEL_KEY = "emitkit.sandbox";

  private static final String DOCKERFILE =
      "FROM alpine:latest\nCOPY testcheck.sh " + CHECK_COMMAND + "\n";
  private static final String CHECK_SCRIPT =
      """
      #!/bin/sh
      cd /work || exit 1
      tar -xf in.tar || exit 1
      sh ./cmd > report.json 2>&1
      exit $?
      """;

  private static DockerClient docker;

  @TempDir Path tmp;

  private DockerSandboxRunner runner;

  @BeforeAll
  static void buildTestImage() throws IOException {
    docker = SandboxConfig.newDockerClient(DOCKER_HOST);
    ByteArrayOutputStream context = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(context)) {
      addFile(tar, "Dockerfile", DOCKERFILE.getBytes(StandardCharsets.UTF_8), 0644);
      addFile(tar, "testcheck.sh", CHECK_SCRIPT.getBytes(StandardCharsets.UTF_8), 0755);
    }
    try (InputStream in = new ByteArrayInputStream(context.toByteArray())) {
      docker
          .buildImageCmd()
          .withTarInputStream(in)
          .withTags(Set.of(IMAGE))
          .exec(new BuildImageResultCallback())
          .awaitImageId();
    }
  }

  @AfterAll
  static void removeTestImage() throws IOException {
    if (docker != null) {
      docker.removeImageCmd(IMAGE).withForce(true).exec();
      docker.close();
    }
  }

  @BeforeEach
  void setUp() {
    runner = newRunner(4);
  }

  @AfterEach
  void noContainersLeft() {
    assertThat(containersLabelled()).isEmpty();
  }

  @Test
  void runsUnderGvisor() {
    assumeTrue("runsc".equals(RUNTIME), "gVisor is only used with runtime=runsc");

    assertThat(exec("dmesg")).containsIgnoringCase("gvisor");
  }

  @Test
  void noNetwork() {
    assertThat(exec("wget -T2 -q -O- http://example.com; echo $?")).endsWith("1");
  }

  @Test
  void cannotWriteOutsideWork() {
    assertThat(exec("touch /etc/x; echo $?")).doesNotEndWith("0");
  }

  @Test
  void runsAsUid10001() {
    assertThat(exec("id -u")).isEqualTo("10001");
  }

  @Test
  void killedAtTimeout() throws IOException {
    long started = System.nanoTime();
    SandboxResult result =
        runner.run(request("sleep 30", Duration.ofSeconds(2)), new CancellationToken());

    assertThat(result.timedOut()).isTrue();
    assertThat(result.outputTar()).isNull();
    assertThat(result.reportJson()).isNull();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(30));
    assertThat(containersLabelled()).isEmpty();
  }

  @Test
  void pidsLimitEnforced() {
    // Each subshell echoes "started" as it begins, then sleeps. Without the limit all 300 start and
    // the report comes back with 300 lines. With --pids-limit 256 the shell dies at a failed fork
    // (the exit code is not checked, see the SBX-01 spike). Under runsc its orphans then hold every
    // pid, so the wrapper's copy-out cannot fork and no report comes back. Under runc the copy-out
    // still fits, and the report shows fewer than 256 started children.
    SandboxResult result =
        runner.run(
            request(
                "i=0; while [ $i -lt 300 ]; do (echo started; sleep 5) & i=$((i+1)); done; wait",
                Duration.ofSeconds(60)),
            new CancellationToken());

    assertThat(result.timedOut()).isFalse();
    if (result.reportJson() != null) {
      long started = result.reportJson().lines().filter("started"::equals).count();
      assertThat(started).isLessThanOrEqualTo(256);
    }
  }

  @Test
  void cancelKillsContainer() throws Exception {
    CancellationToken token = new CancellationToken();
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<SandboxResult> run =
          pool.submit(() -> runner.run(request("sleep 60", Duration.ofSeconds(120)), token));
      awaitRunningContainers(1);

      long cancelledAt = System.nanoTime();
      token.cancel();

      assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt))
          .isLessThan(Duration.ofSeconds(10));
      assertThat(containersLabelled()).isEmpty();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void cancelWhileWaitingForPermit_throwsAndCreatesNoContainer() throws Exception {
    runner = newRunner(1);
    CancellationToken holderToken = new CancellationToken();
    CancellationToken waiterToken = new CancellationToken();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<SandboxResult> holder =
          pool.submit(() -> runner.run(request("sleep 60", Duration.ofSeconds(120)), holderToken));
      awaitRunningContainers(1);

      Future<SandboxResult> waiter =
          pool.submit(() -> runner.run(request("echo never", Duration.ofSeconds(60)), waiterToken));
      waiterToken.cancel();

      assertThatThrownBy(() -> waiter.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      assertThat(containersLabelled()).hasSize(1);

      holderToken.cancel();
      assertThatThrownBy(() -> holder.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void outputTarAndReportCopiedBack() throws IOException {
    SandboxResult result =
        runner.run(
            request("echo payload > out.tar; echo report-text", Duration.ofSeconds(60)),
            new CancellationToken());
    try {
      assertThat(result.timedOut()).isFalse();
      assertThat(result.reportJson()).isEqualTo("report-text\n");
      assertThat(result.outputTar()).isNotNull();
      assertThat(Files.readString(result.outputTar())).isEqualTo("payload\n");
    } finally {
      if (result.outputTar() != null) {
        Files.deleteIfExists(result.outputTar());
      }
    }
  }

  @Test
  void concurrencyLimited() throws Exception {
    runner = newRunner(1);
    // Each run records its own start and end in epoch seconds. With one permit the second run
    // cannot start before the first has ended.
    String command = "echo start $(date +%s); sleep 3; echo end $(date +%s)";
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<SandboxResult> a =
          pool.submit(
              () -> runner.run(request(command, Duration.ofSeconds(60)), new CancellationToken()));
      Future<SandboxResult> b =
          pool.submit(
              () -> runner.run(request(command, Duration.ofSeconds(60)), new CancellationToken()));
      String reportA = a.get(120, TimeUnit.SECONDS).reportJson();
      String reportB = b.get(120, TimeUnit.SECONDS).reportJson();

      boolean aFirst = stamp(reportA, "start") <= stamp(reportB, "start");
      long firstEnd = stamp(aFirst ? reportA : reportB, "end");
      long secondStart = stamp(aFirst ? reportB : reportA, "start");
      assertThat(secondStart).isGreaterThanOrEqualTo(firstEnd);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void daemonUnreachable_isInfraException() throws Exception {
    try (DockerClient unreachable =
        SandboxConfig.newDockerClient("unix:///nonexistent-emitkit/docker.sock")) {
      DockerSandboxRunner unreachableRunner =
          new DockerSandboxRunner(unreachable, properties(DOCKER_HOST, 4));

      assertThatThrownBy(() ->
              unreachableRunner.run(
                  request("echo hi", Duration.ofSeconds(10)), new CancellationToken()))
          .isInstanceOf(SandboxInfraException.class);
    }
  }

  private DockerSandboxRunner newRunner(int maxConcurrent) {
    return new DockerSandboxRunner(docker, properties(DOCKER_HOST, maxConcurrent));
  }

  private static SandboxProperties properties(String dockerHost, int maxConcurrent) {
    return new SandboxProperties(
        dockerHost, RUNTIME, maxConcurrent, Duration.ofSeconds(180), IMAGE, CHECK_COMMAND);
  }

  /** Runs one command in a sandbox and returns its trimmed report. */
  private String exec(String command) {
    return runner
        .run(request(command, Duration.ofSeconds(60)), new CancellationToken())
        .reportJson()
        .strip();
  }

  /** Builds a request whose input tar holds {@code cmd} with the given shell text. */
  private SandboxRequest request(String command, Duration timeout) {
    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (TarArchiveOutputStream tar = new TarArchiveOutputStream(bytes)) {
        addFile(tar, "cmd", (command + "\n").getBytes(StandardCharsets.UTF_8), 0644);
      }
      Path inputTar = Files.createTempFile(tmp, "in-", ".tar");
      Files.write(inputTar, bytes.toByteArray());
      return new SandboxRequest(IMAGE, inputTar, timeout);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static List<Container> containersLabelled() {
    return docker
        .listContainersCmd()
        .withShowAll(true)
        .withLabelFilter(Map.of(LABEL_KEY, "true"))
        .exec();
  }

  private static void awaitRunningContainers(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (containersLabelled().stream().filter(c -> "running".equals(c.getState())).count()
        < count) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("no running sandbox container within 30s");
      }
      Thread.sleep(100);
    }
  }

  /** Reads {@code label <epoch seconds>} from a report. */
  private static long stamp(String report, String label) {
    return report
        .lines()
        .filter(line -> line.startsWith(label + " "))
        .mapToLong(line -> Long.parseLong(line.substring(label.length() + 1).strip()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no '" + label + "' line in: " + report));
  }

  private static void addFile(TarArchiveOutputStream tar, String name, byte[] content, int mode)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    entry.setMode(mode);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }
}
