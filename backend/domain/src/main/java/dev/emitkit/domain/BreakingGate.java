package dev.emitkit.domain;

/**
 * What happens when a spec change is breaking. Stored by name in {@code
 * project_configs.breaking_gate}.
 */
public enum BreakingGate {
  ALLOW,
  REQUIRE_APPROVAL,
  BLOCK
}
