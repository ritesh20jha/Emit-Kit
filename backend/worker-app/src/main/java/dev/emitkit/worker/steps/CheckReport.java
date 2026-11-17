package dev.emitkit.worker.steps;

import java.util.List;

/**
 * The {@code report.json} that check.sh writes (SBX-02). Fields the worker does not use, such as
 * {@code dir}, are ignored.
 */
public record CheckReport(List<ToolResult> results) {

  /** One tool's run: its exit code and its output, capped at 64 KB by check.sh. */
  public record ToolResult(String tool, int exitCode, String output) {}
}
