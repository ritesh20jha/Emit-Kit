package dev.emitkit.domain;

/**
 * The steps of the generation pipeline, in the order they run (spec v1 §5.1). Stored by name in
 * {@code generation_runs.current_step} and {@code generation_run_logs.step}.
 */
public enum StepName {
  FETCH_SPEC,
  PARSE_SPEC,
  LINT_SPEC,
  DIFF_SPEC,
  CALC_VERSION,
  BREAKING_GATE,
  RUN_GENERATORS,
  SYNTAX_CHECK,
  PACKAGE_ARTIFACT,
  COMMIT_OUTPUT,
  FINALIZE
}
