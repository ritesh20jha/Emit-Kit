package dev.emitkit.worker.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.id.Ids;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.RunTrigger;
import dev.emitkit.domain.StepName;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.worker.pipeline.CancellationRegistry;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import dev.emitkit.worker.sandbox.CancellationToken;
import dev.emitkit.worker.sandbox.SandboxInfraException;
import dev.emitkit.worker.sandbox.SandboxProperties;
import dev.emitkit.worker.sandbox.SandboxRequest;
import dev.emitkit.worker.sandbox.SandboxResult;
import dev.emitkit.worker.sandbox.SandboxRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class WRK07SyntaxCheckTest {

  private static final Instant EPOCH = Instant.parse("2026-11-17T10:00:00Z");
  private static final SandboxProperties PROPS =
      new SandboxProperties(
          "unix:///unused", "runsc", 4, Duration.ofSeconds(180), "emitkit/toolchain:test", "x");
  private static final String TS_CLIENT = ".emitkit/sdk/typescript/src/client.ts";
  private static final String PY_INIT = ".emitkit/sdk/python/pkg/__init__.py";
  private static final String TS_LINK = ".emitkit/sdk/typescript/src/link.ts";

  @TempDir Path root;

  private final CapturingLog log = new CapturingLog();
  private final String runId = Ids.newUlid();

  /** Counted down by the first registry lookup, which fails like a database error. */
  private final CountDownLatch dbFailed = new CountDownLatch(1);

  private final CancellationRegistry registry = new CancellationRegistry(runsFailingOnce());

  /** The step's own log lines, kept off the console and checked by the tests that expect them. */
  private final ListAppender<ILoggingEvent> stepLog = new ListAppender<>();

  private Logger stepLogger;
  private WorkDir workDir;

  @BeforeEach
  void captureStepLog() {
    stepLogger = (Logger) LoggerFactory.getLogger(SyntaxCheckStep.class);
    stepLog.start();
    stepLogger.addAppender(stepLog);
    stepLogger.setAdditive(false);
  }

  @AfterEach
  void restoreStepLog() {
    stepLogger.detachAppender(stepLog);
    stepLogger.setAdditive(true);
    // The step restores the interrupt flag on this thread when a sandbox run is interrupted.
    Thread.interrupted();
  }

  @BeforeEach
  void writeGeneratedFiles() {
    workDir = new WorkDir(root.resolve("work"));
    workDir.write(
        runId,
        "generation-1",
        List.of(
            new GeneratedFile(TS_CLIENT, "const a=1\n", FileKind.GENERATED),
            new GeneratedFile(PY_INIT, "x=1", FileKind.GENERATED),
            new GeneratedFile(TS_LINK, "kept\n", FileKind.GENERATED)));
  }

  @Test
  void formattedFilesReplaceOriginals() throws Exception {
    Path out = outTar(Map.of("src/" + TS_CLIENT, "const a = 1;\n", "src/" + PY_INIT, "x = 1\n"));
    String report =
        report(
            "{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\","
                + "\"unknown\":1}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result).isEqualTo(new StepResult.Continue(new SyntaxCheckStep.Output(2)));
    assertThat(runner.sent)
        .containsOnlyKeys("src/" + TS_CLIENT, "src/" + PY_INIT, "src/" + TS_LINK, Tars.LANGUAGES)
        .containsEntry("src/" + TS_CLIENT, "const a=1\n")
        .containsEntry(Tars.LANGUAGES, "python\ntypescript\n");
    assertThat(runner.request.image()).isEqualTo("emitkit/toolchain:test");
    assertThat(runner.request.timeout()).isEqualTo(Duration.ofSeconds(180));
    assertThat(workDir.filesDir(runId).resolve(TS_CLIENT)).hasContent("const a = 1;\n");
    assertThat(workDir.filesDir(runId).resolve(PY_INIT)).hasContent("x = 1\n");
    assertThat(runner.request.inputTar()).doesNotExist();
    assertThat(out).doesNotExist();
  }

  @Test
  void failingReport_failsCheckFailed_logsOutput() throws Exception {
    Path out = outTar(Map.of("src/" + TS_CLIENT, "const a = 1;\n"));
    String report =
        report(
            "{\"tool\":\"prettier\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\"},"
                + "{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":2,"
                + "\"output\":\"src/client.ts(1,1): error TS2322\\n\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result)
        .isEqualTo(new StepResult.Fail(ErrorCode.CHECK_FAILED, "tsc failed in sdk/typescript"));
    assertThat(log.errors).contains("tsc in sdk/typescript exited with 2:");
    assertThat(log.errors).contains("src/client.ts(1,1): error TS2322");
    assertThat(workDir.filesDir(runId).resolve(TS_CLIENT)).hasContent("const a=1\n");
    assertThat(runner.request.inputTar()).doesNotExist();
    assertThat(out).doesNotExist();
  }

  @Test
  void timeout_failsSandboxTimeout() {
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(null, null, true));

    StepResult result = step(runner).execute(context());

    assertThat(result)
        .isInstanceOfSatisfying(
            StepResult.Fail.class,
            fail -> assertThat(fail.code()).isEqualTo(ErrorCode.SANDBOX_TIMEOUT));
  }

  @Test
  void infraError_isTransient() {
    FakeRunner runner =
        new FakeRunner(req -> {
          throw new SandboxInfraException("docker daemon is down", null);
        });
    SyntaxCheckStep step = step(runner);
    RunContext ctx = context();

    assertThatThrownBy(() -> step.execute(ctx)).isInstanceOf(TransientException.class);
  }

  @Test
  void unexpectedNewFileInOutput_ignored() throws Exception {
    Path out =
        outTar(
            Map.of(
                "src/" + TS_CLIENT,
                "formatted\n",
                "src/.emitkit/sdk/typescript/src/new.ts",
                "new\n",
                "src/../escape.ts",
                "escaped\n"),
            Map.of("src/" + TS_LINK, "/etc/passwd"));
    String report =
        report("{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result).isEqualTo(new StepResult.Continue(new SyntaxCheckStep.Output(1)));
    assertThat(workDir.filesDir(runId).resolve(TS_CLIENT)).hasContent("formatted\n");
    assertThat(workDir.filesDir(runId).resolve(TS_LINK)).hasContent("kept\n");
    assertThat(workDir.filesDir(runId).resolve(".emitkit/sdk/typescript/src/new.ts"))
        .doesNotExist();
    assertThat(workDir.of(runId).resolve("escape.ts")).doesNotExist();
  }

  @Test
  void cancelDuringSandboxRun_cancelsTokenAndThrowsCancelled() {
    SandboxRunner blocking =
        (req, token) -> {
          registry.add(runId);
          awaitCancelled(token);
          throw new CancellationException("sandbox run cancelled");
        };

    assertThatThrownBy(() -> step(blocking).execute(context()))
        .isInstanceOf(CancelledException.class);
  }

  @Test
  void cancelSurvivesFailedDatabaseCheck() {
    // The poller's first lookup fails; it must keep polling so the later cancel is seen.
    SandboxRunner blocking =
        (req, token) -> {
          await(dbFailed, "the first cancellation check never ran");
          registry.add(runId);
          awaitCancelled(token);
          throw new CancellationException("sandbox run cancelled");
        };

    assertThatThrownBy(() -> step(blocking).execute(context()))
        .isInstanceOf(CancelledException.class);
    assertThat(stepLog.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains("could not check whether run"));
  }

  @Test
  void truncatedOutput_failsCheckFailed_filesUntouched() throws Exception {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("src/" + PY_INIT, "x = 1\n");
    files.put("src/" + TS_CLIENT, "y".repeat(4096));
    Path out = outTar(files, Map.of());
    // The python entry takes 1024 bytes and the client header 512; cut inside the client data.
    Files.write(out, Arrays.copyOf(Files.readAllBytes(out), 2048));
    String report =
        report("{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result)
        .isEqualTo(new StepResult.Fail(ErrorCode.CHECK_FAILED, "sandbox output is unreadable"));
    assertThat(workDir.filesDir(runId).resolve(TS_CLIENT)).hasContent("const a=1\n");
    assertThat(workDir.filesDir(runId).resolve(PY_INIT)).hasContent("x=1");
    try (Stream<Path> entries = Files.list(workDir.of(runId))) {
      assertThat(entries.map(p -> p.getFileName().toString()))
          .noneMatch(name -> name.startsWith("syntaxcheck-"));
    }
  }

  @Test
  void oversizedOutputFile_failsCheckFailed_filesUntouched() throws Exception {
    // The original client file is 10 bytes, so its limit is the 1 MiB floor.
    Map<String, String> files = new LinkedHashMap<>();
    files.put("src/" + PY_INIT, "x = 1\n");
    files.put("src/" + TS_CLIENT, "y".repeat(2 << 20));
    Path out = outTar(files, Map.of());
    String report =
        report("{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result)
        .isEqualTo(
            new StepResult.Fail(
                ErrorCode.CHECK_FAILED,
                "sandbox output file " + TS_CLIENT + " is over its size limit"));
    assertThat(workDir.filesDir(runId).resolve(TS_CLIENT)).hasContent("const a=1\n");
    assertThat(workDir.filesDir(runId).resolve(PY_INIT)).hasContent("x=1");
  }

  @Test
  void missingExitCode_failsCheckFailed() {
    String report = report("{\"tool\":\"tsc\",\"output\":\"no code\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(null, report, false));

    StepResult result = step(runner).execute(context());

    assertThat(result).isEqualTo(new StepResult.Fail(ErrorCode.CHECK_FAILED, "tsc failed"));
    assertThat(log.errors).contains("tsc no exit code:");
  }

  @Test
  void sandboxInterrupted_isTransientNotCancelled() {
    // A CancellationException with the token not cancelled is a thread interrupt, not a cancel.
    SandboxRunner interrupted =
        (req, token) -> {
          throw new CancellationException("sandbox run cancelled");
        };
    SyntaxCheckStep step = step(interrupted);
    RunContext ctx = context();

    assertThatThrownBy(() -> step.execute(ctx))
        .isInstanceOf(TransientException.class)
        .isNotInstanceOf(CancelledException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
  }

  @Test
  void languagesOfJavaCliAndMcpFiles_areListed() throws Exception {
    workDir.write(
        runId,
        "generation-2",
        List.of(
            new GeneratedFile(
                ".emitkit/sdk/java/src/Client.java", "class Client {}", FileKind.GENERATED),
            new GeneratedFile(".emitkit/cli/src/index.ts", "export {};\n", FileKind.GENERATED),
            new GeneratedFile(".emitkit/mcp/src/server.ts", "export {};\n", FileKind.GENERATED),
            new GeneratedFile(".emitkit/docs/v1/index.md", "# docs\n", FileKind.GENERATED)));
    Path out = outTar(Map.of());
    String report = report("{\"tool\":\"tsc\",\"dir\":\"cli\",\"exitCode\":0,\"output\":\"\"}");
    FakeRunner runner = new FakeRunner(req -> new SandboxResult(out, report, false));

    step(runner).execute(context());

    assertThat(runner.sent).containsEntry(Tars.LANGUAGES, "java\ntypescript\n");
  }

  private SyntaxCheckStep step(SandboxRunner runner) {
    return new SyntaxCheckStep(runner, PROPS, workDir);
  }

  private RunContext context() {
    GenerationRun run =
        new GenerationRun(runId, "project-1", "config-1", RunTrigger.MANUAL, false, EPOCH);
    ProjectConfig config =
        new ProjectConfig("config-1", "project-1", 1, VersionStrategy.EMITKIT_MANAGED, "user-1");
    return new RunContext(
        run,
        null,
        config,
        () -> {
          throw new AssertionError("the syntax check does not use VCS");
        },
        log,
        registry);
  }

  /** A run repository whose first lookup fails, as a database error would. */
  private GenerationRunRepository runsFailingOnce() {
    AtomicBoolean first = new AtomicBoolean(true);
    return (GenerationRunRepository)
        Proxy.newProxyInstance(
            GenerationRunRepository.class.getClassLoader(),
            new Class<?>[] {GenerationRunRepository.class},
            (proxy, method, args) -> {
              if (first.getAndSet(false)) {
                dbFailed.countDown();
                throw new IllegalStateException("database unavailable");
              }
              return method.getReturnType() == Optional.class ? Optional.empty() : null;
            });
  }

  /** Blocks until {@code token} is cancelled, failing the test if that takes too long. */
  private static void awaitCancelled(CancellationToken token) {
    CountDownLatch cancelled = new CountDownLatch(1);
    token.onCancel(cancelled::countDown);
    await(cancelled, "the sandbox token was never cancelled");
  }

  private static void await(CountDownLatch latch, String failure) {
    try {
      if (!latch.await(10, TimeUnit.SECONDS)) {
        throw new AssertionError(failure);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static String report(String... tools) {
    return "{\"results\":[" + String.join(",", tools) + "]}";
  }

  private Path outTar(Map<String, String> files) throws IOException {
    return outTar(files, Map.of());
  }

  /** A tar shaped like check.sh's out.tar: regular files with content, and symlinks. */
  private Path outTar(Map<String, String> files, Map<String, String> symlinks) throws IOException {
    Path tar = Files.createTempFile(root, "out-", ".tar");
    try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(tar))) {
      out.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (Map.Entry<String, String> file : files.entrySet()) {
        byte[] bytes = file.getValue().getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(file.getKey());
        entry.setSize(bytes.length);
        out.putArchiveEntry(entry);
        out.write(bytes);
        out.closeArchiveEntry();
      }
      for (Map.Entry<String, String> link : symlinks.entrySet()) {
        TarArchiveEntry entry = new TarArchiveEntry(link.getKey(), TarConstants.LF_SYMLINK);
        entry.setLinkName(link.getValue());
        out.putArchiveEntry(entry);
        out.closeArchiveEntry();
      }
    }
    return tar;
  }

  /** The regular files of a tar, by name. */
  private static Map<String, String> readTar(Path tar) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (TarArchiveInputStream in = new TarArchiveInputStream(Files.newInputStream(tar))) {
      for (TarArchiveEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
        if (entry.isFile()) {
          entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
      }
    }
    return entries;
  }

  /** Returns a canned result and records the request and the input tar the sandbox was sent. */
  private static final class FakeRunner implements SandboxRunner {

    private final Function<SandboxRequest, SandboxResult> behavior;
    SandboxRequest request;
    Map<String, String> sent;

    FakeRunner(Function<SandboxRequest, SandboxResult> behavior) {
      this.behavior = behavior;
    }

    @Override
    public SandboxResult run(SandboxRequest req, CancellationToken token) {
      request = req;
      try {
        sent = readTar(req.inputTar());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return behavior.apply(req);
    }
  }

  private static final class CapturingLog implements RunLogger {

    final List<String> errors = new ArrayList<>();

    @Override
    public void info(String message) {}

    @Override
    public void warn(String message) {}

    @Override
    public void error(String message) {
      errors.add(message);
    }

    @Override
    public void mask(String secret) {}

    @Override
    public void step(StepName step) {}

    @Override
    public void flush() {}

    @Override
    public void done() {}
  }
}
