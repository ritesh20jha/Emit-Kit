package dev.emitkit.domain;

/** How a generated file differs from the same path on the target branch. */
public enum FileStatus {
  ADDED,
  MODIFIED,
  UNCHANGED,
  SKIPPED
}
