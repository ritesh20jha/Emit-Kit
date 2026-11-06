package dev.emitkit.domain;

/** A kind of generated output. Stored by name inside {@code project_configs.outputs} (JSONB). */
public enum OutputKind {
  SDK,
  CLI,
  MCP,
  DOCS
}
