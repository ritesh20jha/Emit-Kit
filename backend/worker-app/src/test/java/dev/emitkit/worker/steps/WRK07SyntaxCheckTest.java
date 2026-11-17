package dev.emitkit.worker.steps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
  private WorkDir workDir;

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
        report("{\"tool\":\"tsc\",\"dir\":\"sdk/typescript\",\"exitCode\":0,\"output\":\"\"}");
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

    assertThat(result).isEqualTo(new StepResult.Fail(ErrorCode.CHECK_FAILED, "tsc failed"));
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
        new CancellationRegistry(noRuns()));
  }

  private static GenerationRunRepository noRuns() {
    return (GenerationRunRepository)
        Proxy.newProxyInstance(
            GenerationRunRepository.class.getClassLoader(),
            new Class<?>[] {GenerationRunRepository.class},
            (proxy, method, args) ->
                method.getReturnType() == Optional.class ? Optional.empty() : null);
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
