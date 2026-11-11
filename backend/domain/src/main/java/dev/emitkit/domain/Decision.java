package dev.emitkit.domain;

/** A reviewer's answer to a run that waits for approval. Stored by name in the approval record. */
public enum Decision {
  APPROVED,
  REJECTED
}
