package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerMount;
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
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
  private static final String IO_DIR = "/io";

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
    assertThat(testContainers()).isEmpty();
  }

  @Test
  void runsUnderGvisor() {
    assumeTrue("runsc".equals(RUNTIME), "gVisor is only used with runtime=runsc");

    assertThat(exec("dmesg")).containsIgnoringCase("gvisor");
  }

  @Test
  void noNetwork() {
    // Only the loopback interface may exist. Checking the interface list means an offline failure
    // of wget alone cannot pass this test.
    List<String> interfaces =
        exec("cat /proc/net/dev")
            .lines()
            .filter(line -> line.contains(":"))
            .map(line -> line.substring(0, line.indexOf(':')).strip())
            .toList();

    assertThat(interfaces).containsExactly("lo");
    assertThat(exec("wget -T2 -q -O- http://example.com; echo $?")).endsWith("1");
  }

  @Test
  void cannotWriteOutsideWork() {
    // /var/tmp is world-writable on the rootfs, so uid 10001 could write there without the flag and
    // the failure below comes from --read-only. /tmp is not used: under runsc it is a tmpfs mount.
    assertThat(exec("touch /var/tmp/x 2>&1; echo $?"))
        .contains("Read-only file system")
        .endsWith("1");
    // Positive control: /work is writable, so the write above fails for the flag, not permissions.
    assertThat(exec("touch /work/x 2>&1; echo $?")).isEqualTo("0");
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
    assertThat(testContainers()).isEmpty();
  }

  @Test
  void pidsLimitEnforced() {
    // xargs forks the children itself, so it reaps them and survives a failed fork. The shell that
    // forks a fork bomb cannot survive one (busybox exits), so the children must not be its own.
    // With --pids-limit 256 fewer than 300 start. Without the limit all 300 start.
    SandboxResult result =
        runner.run(
            request(
                ": > /work/started; "
                    + "seq 300 | xargs -P 300 -n 1 sh -c 'echo started >> /work/started; sleep 3'; "
                    + "n=0; while read line; do n=$((n+1)); done < /work/started; "
                    + "echo \"done count=$n\"",
                Duration.ofSeconds(60)),
            new CancellationToken());

    assertThat(result.timedOut()).isFalse();
    String report = result.reportJson();
    assertThat(report).isNotNull();
    Matcher count = Pattern.compile("done count=(\\d+)").matcher(report);
    assertThat(count.find()).as("report: %s", report).isTrue();
    assertThat(Long.parseLong(count.group(1))).isLessThanOrEqualTo(253);
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
      assertThat(testContainers()).isEmpty();
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
      assertThat(testContainers()).hasSize(1);

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
  void interruptedRun_removesContainerAndVolume() throws Exception {
    AtomicReference<Throwable> thrown = new AtomicReference<>();
    AtomicBoolean flagRestored = new AtomicBoolean();
    Thread worker =
        new Thread(() -> {
          try {
            runner.run(request("sleep 60", Duration.ofSeconds(120)), new CancellationToken());
          } catch (Throwable t) {
            thrown.set(t);
          } finally {
            flagRestored.set(Thread.currentThread().isInterrupted());
          }
        });
    worker.start();
    awaitRunningContainers(1);
    List<String> volumes = volumesAt(IO_DIR, testContainers().get(0));

    worker.interrupt();
    worker.join(30_000);

    assertThat(worker.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(CancellationException.class);
    assertThat(flagRestored.get()).isTrue();
    assertThat(volumes).isNotEmpty();
    assertThat(testContainers()).isEmpty();
    for (String volume : volumes) {
      assertThatThrownBy(() -> docker.inspectVolumeCmd(volume).exec())
          .isInstanceOf(NotFoundException.class);
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

  /** Containers started from this test's image, so other containers on the daemon are ignored. */
  private static List<Container> testContainers() {
    return docker.listContainersCmd().withShowAll(true).withAncestorFilter(List.of(IMAGE)).exec();
  }

  private static List<String> volumesAt(String destination, Container container) {
    return container.getMounts().stream()
        .filter(mount -> destination.equals(mount.getDestination()))
        .map(ContainerMount::getName)
        .toList();
  }

  private static void awaitRunningContainers(int count) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (testContainers().stream().filter(c -> "running".equals(c.getState())).count() < count) {
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
