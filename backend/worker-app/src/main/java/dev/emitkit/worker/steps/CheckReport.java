package dev.emitkit.worker.steps;

import java.util.List;

/**
 * The {@code report.json} that check.sh writes (SBX-02). Fields the worker does not use are
 * ignored.
 */
public record CheckReport(List<ToolResult> results) {

  /**
   * One tool's run. {@code dir} is null when the report gives none. {@code exitCode} is null when
   * the report omits it, and the worker counts that as a failure. {@code output} is capped at 64 KB
   * by check.sh.
   */
  public record ToolResult(String tool, String dir, Integer exitCode, String output) {}
}
