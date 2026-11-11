package dev.emitkit.spec.diff;

import java.util.List;
import java.util.Objects;

/** An operation present on both sides whose contract changed. */
public record OpChange(OpRef op, List<Change> changes) {

  public OpChange {
    Objects.requireNonNull(op, "op");
    changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
  }
}
