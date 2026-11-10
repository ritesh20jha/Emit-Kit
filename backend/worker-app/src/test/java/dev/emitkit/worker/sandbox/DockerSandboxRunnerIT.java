package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.command.BuildImageResultCallback;
import dev.emitkit.common.id.Ids;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Escape suite for {@link DockerSandboxRunner}, run on the daemon named by {@code
 * sandbox.docker-host}.
 */
class DockerSandboxRunnerIT {

  private static final String RUNTIME = System.getProperty("sandbox.runtime", "runsc");
  private static final String DOCKER_HOST =
      System.getProperty("sandbox.docker-host", "unix:///var/run/docker.sock");
  private static final String CHECK = "/usr/local/bin/testcheck.sh";
  private static final String IMAGE = "emitkit/sandbox-it:" + Ids.newUlid();
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

  private DockerSandboxRunner runner = newRunner(docker, 4);

  @BeforeAll
  static void buildTestImage() throws IOException {
    docker = SandboxConfig.newDockerClient(DOCKER_HOST);
    ByteArrayOutputStream context = new ByteArrayOutputStream();
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(context)) {
      addFile(tar, "Dockerfile", "FROM alpine:latest\nCOPY testcheck.sh " + CHECK + "\n", 0644);
      addFile(tar, "testcheck.sh", CHECK_SCRIPT, 0755);
    }
    var build = docker.buildImageCmd().withTags(Set.of(IMAGE));
    build
        .withTarInputStream(new ByteArrayInputStream(context.toByteArray()))
        .exec(new BuildImageResultCallback())
        .awaitImageId();
  }

  @AfterAll
  static void removeTestImage() throws IOException {
    if (docker != null) {
      docker.removeImageCmd(IMAGE).withForce(true).exec();
      docker.close();
    }
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
    // Only loopback may exist, so an offline wget failure alone cannot pass this test.
    assertThat(exec("awk -F: 'NR>2{print $1}' /proc/net/dev")).isEqualTo("lo");
    assertThat(exec("wget -T2 -q -O- http://example.com; echo $?")).endsWith("1");
  }

  @Test
  void cannotWriteOutsideWork() {
    // /var/tmp is world-writable on the rootfs, so --read-only is the cause (/tmp is a tmpfs here).
    assertThat(exec("touch /var/tmp/x 2>&1; echo $?"))
        .contains("Read-only file system")
        .endsWith("1");
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
    // xargs forks and reaps the children, so the shell survives. A forking shell cannot survive a
    // failed fork, hence xargs. With --pids-limit 256 fewer than 300 children start.
    String command =
        ": > /work/started; seq 300 | xargs -P 300 -n 1 sh -c 'echo started >> /work/started; sleep 3'; "
            + "n=0; while read line; do n=$((n+1)); done < /work/started; echo \"done count=$n\"";
    SandboxResult result =
        runner.run(request(command, Duration.ofSeconds(60)), new CancellationToken());

    assertThat(result.timedOut()).isFalse();
    String report = result.reportJson();
    assertThat(report).isNotNull();
    Matcher count = Pattern.compile("done count=(\\d+)").matcher(report);
    assertThat(count.find()).as("report: %s", report).isTrue();
    long started = Long.parseLong(count.group(1));
    assertThat(started).isGreaterThan(50).isLessThanOrEqualTo(270);
  }

  @Test
  void cancelKillsContainer() throws Exception {
    CancellationToken token = new CancellationToken();
    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
      Future<SandboxResult> run = submit(pool, "sleep 60", token);
      awaitRunningContainers(1);

      long cancelledAt = System.nanoTime();
      token.cancel();

      assertThatThrownBy(() -> run.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt))
          .isLessThan(Duration.ofSeconds(10));
      assertThat(testContainers()).isEmpty();
    }
  }

  @Test
  void cancelWhileWaitingForPermit_throwsAndCreatesNoContainer() throws Exception {
    runner = newRunner(docker, 1);
    CancellationToken holderToken = new CancellationToken();
    CancellationToken waiterToken = new CancellationToken();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<SandboxResult> holder = submit(pool, "sleep 60", holderToken);
      awaitRunningContainers(1);
      Future<SandboxResult> waiter = submit(pool, "echo never", waiterToken);

      waiterToken.cancel();
      assertThatThrownBy(() -> waiter.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
      assertThat(testContainers()).hasSize(1);

      holderToken.cancel();
      assertThatThrownBy(() -> holder.get(30, TimeUnit.SECONDS))
          .hasCauseInstanceOf(CancellationException.class);
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
      assertThat(Files.readString(result.outputTar())).isEqualTo("payload\n");
    } finally {
      if (result.outputTar() != null) {
        Files.deleteIfExists(result.outputTar());
      }
    }
  }

  @Test
  void concurrencyLimited() throws Exception {
    runner = newRunner(docker, 1);
    // Each run records its start and end in epoch seconds. With one permit the second run cannot
    // start before the first has ended.
    String command = "echo start $(date +%s); sleep 3; echo end $(date +%s)";
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
      Future<SandboxResult> a = submit(pool, command, new CancellationToken());
      Future<SandboxResult> b = submit(pool, command, new CancellationToken());
      String reportA = a.get(120, TimeUnit.SECONDS).reportJson();
      String reportB = b.get(120, TimeUnit.SECONDS).reportJson();

      // Order the runs by the start time the containers wrote, not by submission order.
      boolean aFirst = stamp(reportA, "start") <= stamp(reportB, "start");
      String first = aFirst ? reportA : reportB;
      String second = aFirst ? reportB : reportA;
      assertThat(stamp(second, "start")).isGreaterThanOrEqualTo(stamp(first, "end"));
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
    List<String> volumes =
        testContainers().get(0).getMounts().stream()
            .filter(mount -> "/io".equals(mount.getDestination()))
            .map(mount -> mount.getName())
            .toList();
    assertThat(volumes).isNotEmpty();

    worker.interrupt();
    worker.join(30_000);

    assertThat(worker.isAlive()).isFalse();
    assertThat(thrown.get()).isInstanceOf(CancellationException.class);
    assertThat(flagRestored.get()).isTrue();
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
      var unreachableRunner = newRunner(unreachable, 4);
      SandboxRequest req = request("echo hi", Duration.ofSeconds(10));

      assertThatThrownBy(() -> unreachableRunner.run(req, new CancellationToken()))
          .isInstanceOf(SandboxInfraException.class);
    }
  }

  private static DockerSandboxRunner newRunner(DockerClient client, int maxConcurrent) {
    return new DockerSandboxRunner(
        client,
        new SandboxProperties(
            DOCKER_HOST, RUNTIME, maxConcurrent, Duration.ofSeconds(180), IMAGE, CHECK));
  }

  private Future<SandboxResult> submit(
      ExecutorService pool, String command, CancellationToken token) {
    return pool.submit(() -> runner.run(request(command, Duration.ofSeconds(120)), token));
  }

  private String exec(String command) {
    return runner
        .run(request(command, Duration.ofSeconds(60)), new CancellationToken())
        .reportJson()
        .strip();
  }

  /** A request whose input tar holds the file {@code cmd} with the given shell text. */
  private SandboxRequest request(String command, Duration timeout) {
    try {
      Path inputTar = Files.createTempFile(tmp, "in-", ".tar");
      try (TarArchiveOutputStream tar =
          new TarArchiveOutputStream(Files.newOutputStream(inputTar))) {
        addFile(tar, "cmd", command + "\n", 0644);
      }
      return new SandboxRequest(IMAGE, inputTar, timeout);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Containers started from this test's image, so other containers on the daemon are ignored. */
  private static List<Container> testContainers() {
    return docker.listContainersCmd().withShowAll(true).withAncestorFilter(List.of(IMAGE)).exec();
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

  /** Reads the epoch-seconds value of a {@code label N} line from a report. */
  private static long stamp(String report, String label) {
    Matcher m = Pattern.compile("(?m)^" + label + " (\\d+)").matcher(report);
    assertThat(m.find()).as("report: %s", report).isTrue();
    return Long.parseLong(m.group(1));
  }

  private static void addFile(TarArchiveOutputStream tar, String name, String content, int mode)
      throws IOException {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(bytes.length);
    entry.setMode(mode);
    tar.putArchiveEntry(entry);
    tar.write(bytes);
    tar.closeArchiveEntry();
  }
}
