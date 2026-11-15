package dev.emitkit.domain;

import java.time.Instant;

/**
 * The columns of a snapshot that a list needs. A query for this type selects only these columns, so
 * the spec text and the parsed model are not loaded.
 */
public interface SpecSnapshotSummary {

  String getId();

  String getContentHash();

  String getInfoVersion();

  Instant getCreatedAt();
}
