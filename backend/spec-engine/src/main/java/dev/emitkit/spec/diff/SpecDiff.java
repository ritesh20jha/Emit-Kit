package dev.emitkit.spec.diff;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * The difference between two versions of an API spec. The JSON form is exactly {@code added},
 * {@code removed} and {@code modified}; the derived checks are not serialized.
 *
 * @param added operations that exist only in the newer spec
 * @param removed operations that exist only in the older spec
 * @param modified operations in both specs that changed
 */
public record SpecDiff(
    @Schema(requiredMode = REQUIRED) List<OpRef> added,
    @Schema(requiredMode = REQUIRED) List<OpRef> removed,
    @Schema(requiredMode = REQUIRED) List<OpChange> modified) {

  /** A diff with no changes at all. */
  public static SpecDiff empty() {
    return new SpecDiff(List.of(), List.of(), List.of());
  }

  /** True when an operation was removed or any change is breaking. */
  public boolean hasBreaking() {
    return !removed.isEmpty()
        || modified.stream().flatMap(op -> op.changes().stream()).anyMatch(Change::breaking);
  }

  /** True when there are no additions, removals or modifications. */
  @JsonIgnore
  public boolean isEmpty() {
    return added.isEmpty() && removed.isEmpty() && modified.isEmpty();
  }
}
