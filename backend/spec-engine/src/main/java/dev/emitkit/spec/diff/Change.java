package dev.emitkit.spec.diff;

import java.util.Objects;

/**
 * One change within an operation. {@code location} is e.g. {@code "query.limit"} or {@code "body"}.
 */
public record Change(ChangeKind kind, String location, boolean breaking) {

  public Change {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(location, "location");
  }
}
