package dev.emitkit.domain;

/** What started a generation run. Stored by name in {@code generation_runs.trigger}. */
public enum RunTrigger {
  MANUAL,
  WEBHOOK,
  RERUN
}
