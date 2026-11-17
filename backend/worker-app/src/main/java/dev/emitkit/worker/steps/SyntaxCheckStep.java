package dev.emitkit.worker.steps;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.domain.StepName;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import dev.emitkit.worker.sandbox.CancellationToken;
import dev.emitkit.worker.sandbox.SandboxProperties;
import dev.emitkit.worker.sandbox.SandboxRequest;
import dev.emitkit.worker.sandbox.SandboxResult;
import dev.emitkit.worker.sandbox.SandboxRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Compile-checks and formats the generated files in the sandbox (spec v1 §5.1 step 8). The
 * formatted files replace the work dir's files on success. A failed check fails the run and leaves
 * the files as generated.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class SyntaxCheckStep implements PipelineStep {

  private static final Duration TIMEOUT = Duration.ofSeconds(180);

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
  private static final int MAX_LOG_LINES = 200;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Logger LOG = LoggerFactory.getLogger(SyntaxCheckStep.class);

  private final SandboxRunner sandbox;
  private final SandboxProperties properties;
  private final WorkDir workDir;

  @Autowired
  public SyntaxCheckStep(SandboxRunner sandbox, SandboxProperties properties, WorkDir workDir) {
    this.sandbox = sandbox;
    this.properties = properties;
    this.workDir = workDir;
  }

  @Override
  public StepName name() {
    return StepName.SYNTAX_CHECK;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    String runId = ctx.run().getId();
    List<WorkDir.Entry> files = workDir.manifest(runId);
    Set<String> languages = languagesOf(files, ctx.config().getOutputDir());
    if (languages.isEmpty()) {
      ctx.log().info("no files to check");
      return new StepResult.Continue(new Output(0));
    }
    List<String> paths = files.stream().map(WorkDir.Entry::path).toList();
    Path filesDir = workDir.filesDir(runId);
    Path inTar = null;
    Path stage = null;
    SandboxResult result = null;
    try {
      inTar = Files.createTempFile("syntaxcheck-in-", ".tar");
      Tars.writeInput(inTar, filesDir, paths, languages);
      result = runSandbox(ctx, inTar);
      Optional<StepResult.Fail> failure = failureOf(result, ctx.log());
      if (failure.isPresent()) {
        return failure.get();
      }
      if (result.outputTar() == null) {
        return new StepResult.Fail(ErrorCode.CHECK_FAILED, "sandbox produced no formatted files");
      }
      // Read the whole output into a staging dir first, so a bad archive leaves the files alone.
      stage = Files.createTempDirectory(workDir.of(runId), "syntaxcheck-");
      Map<String, Path> formatted =
          Tars.stage(result.outputTar(), stage, filesDir, Set.copyOf(paths));
      Tars.install(formatted, filesDir);
      return new StepResult.Continue(new Output(formatted.size()));
    } catch (Tars.RejectedOutputException e) {
      return new StepResult.Fail(ErrorCode.CHECK_FAILED, e.getMessage());
    } catch (IOException e) {
      throw new TransientException(
          ErrorCode.STORAGE_ERROR, "generated files could not be checked", Map.of(), e);
    } finally {
      Tars.deleteQuietly(inTar);
      Tars.deleteQuietly(stage);
      if (result != null) {
        Tars.deleteQuietly(result.outputTar());
      }
    }
  }

  /** The number of distinct files replaced by their formatted versions. */
  public record Output(int formattedFileCount) {}

  /**
   * Runs the sandbox with a token that is cancelled when the run is asked to stop. A cancelled
   * sandbox run becomes {@link CancelledException}.
   */
  private SandboxResult runSandbox(RunContext ctx, Path inTar) {
    CancellationToken token = new CancellationToken();
    Thread poller = Thread.ofVirtual().start(() -> cancelWhenStopped(ctx, token));
    try {
      return sandbox.run(new SandboxRequest(properties.image(), inTar, TIMEOUT), token);
    } catch (CancellationException e) {
      throw new CancelledException(ctx.run().getId());
    } finally {
      poller.interrupt();
    }
  }

  /**
   * Cancels the token once the run is asked to stop. A failed check is logged and retried on the
   * next tick, so one database error does not end the polling. Returns when interrupted.
   */
  private static void cancelWhenStopped(RunContext ctx, CancellationToken token) {
    try {
      while (true) {
        Thread.sleep(POLL_INTERVAL);
        try {
          ctx.checkCancelled();
        } catch (CancelledException e) {
          token.cancel();
          return;
        } catch (RuntimeException e) {
          LOG.warn("could not check whether run {} was cancelled; retrying", ctx.run().getId(), e);
        }
      }
    } catch (InterruptedException e) {
      // The sandbox run has returned.
    }
  }

  /** The failure that a sandbox result reports, or empty if every tool passed. */
  private static Optional<StepResult.Fail> failureOf(SandboxResult result, RunLogger log) {
    if (result.timedOut()) {
      return Optional.of(
          new StepResult.Fail(
              ErrorCode.SANDBOX_TIMEOUT,
              "syntax check timed out after " + TIMEOUT.toSeconds() + " s"));
    }
    if (result.reportJson() == null) {
      return Optional.of(new StepResult.Fail(ErrorCode.CHECK_FAILED, "sandbox produced no report"));
    }
    Optional<List<CheckReport.ToolResult>> results = toolResults(result.reportJson());
    if (results.isEmpty()) {
      return Optional.of(
          new StepResult.Fail(ErrorCode.CHECK_FAILED, "sandbox report is unreadable"));
    }
    List<CheckReport.ToolResult> failed =
        results.get().stream().filter(tool -> tool.exitCode() != 0).toList();
    failed.forEach(tool -> logOutput(log, tool));
    return failed.stream()
        .findFirst()
        .map(tool -> new StepResult.Fail(ErrorCode.CHECK_FAILED, tool.tool() + " failed"));
  }

  private static Optional<List<CheckReport.ToolResult>> toolResults(String reportJson) {
    try {
      return Optional.ofNullable(JSON.readValue(reportJson, CheckReport.class).results());
    } catch (JacksonException e) {
      return Optional.empty();
    }
  }

  private static void logOutput(RunLogger log, CheckReport.ToolResult tool) {
    log.error(tool.tool() + " exited with " + tool.exitCode() + ":");
    String output = tool.output() == null ? "" : tool.output();
    output.lines().limit(MAX_LOG_LINES).forEach(log::error);
  }

  /**
   * The languages whose checks cover the files. A path under {@code outputDir} maps by its
   * directory: {@code sdk/typescript}, {@code cli} and {@code mcp} are TypeScript, {@code
   * sdk/python} is Python and {@code sdk/java} is Java. Other files need no check.
   */
  private static Set<String> languagesOf(List<WorkDir.Entry> files, String outputDir) {
    String base = outputDir.replaceAll("^/+|/+$", "");
    Set<String> languages = new TreeSet<>();
    for (WorkDir.Entry file : files) {
      String rel = relativeTo(base, file.path());
      if (rel == null) {
        continue;
      }
      if (rel.startsWith("sdk/typescript/") || rel.startsWith("cli/") || rel.startsWith("mcp/")) {
        languages.add("typescript");
      } else if (rel.startsWith("sdk/python/")) {
        languages.add("python");
      } else if (rel.startsWith("sdk/java/")) {
        languages.add("java");
      }
    }
    return languages;
  }

  /** The path relative to {@code base}, or null if the path is not under it. */
  private static String relativeTo(String base, String path) {
    if (base.isEmpty()) {
      return path;
    }
    return path.startsWith(base + "/") ? path.substring(base.length() + 1) : null;
  }
}
