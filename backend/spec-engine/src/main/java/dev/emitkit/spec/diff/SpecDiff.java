package dev.emitkit.spec.diff;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Objects;

/**
 * Result of comparing two models. {@code added} and {@code removed} follow the order of the model
 * they come from; {@code modified} follows the after model and lists only operations with changes.
 */
public record SpecDiff(List<OpRef> added, List<OpRef> removed, List<OpChange> modified) {

  public SpecDiff {
    added = List.copyOf(Objects.requireNonNull(added, "added"));
    removed = List.copyOf(Objects.requireNonNull(removed, "removed"));
    modified = List.copyOf(Objects.requireNonNull(modified, "modified"));
  }

  public static SpecDiff empty() {
    return new SpecDiff(List.of(), List.of(), List.of());
  }

  /** True when an operation was removed or any change is breaking. */
  public boolean hasBreaking() {
    return !removed.isEmpty()
        || modified.stream().flatMap(m -> m.changes().stream()).anyMatch(Change::breaking);
  }

  /** True when nothing was added, removed, or modified. Not serialized: it is derived state. */
  @JsonIgnore
  public boolean isEmpty() {
    return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
  }
}
