package dev.emitkit.spec.diff;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Compares two API models. Operations match purely by operationId: a changed method or path under
 * the same id is not a change. When a model repeats an operationId, the first occurrence wins.
 */
public final class SpecDiffer {

  private final SchemaDiffer schemaDiffer;

  /** Operation and parameter rules only; schema comparisons report nothing. */
  public SpecDiffer() {
    this(SchemaDiffer.NONE);
  }

  public SpecDiffer(SchemaDiffer schemaDiffer) {
    this.schemaDiffer = Objects.requireNonNull(schemaDiffer, "schemaDiffer");
  }

  /**
   * Diffs {@code before} against {@code after}. A null {@code before} reports everything as added.
   */
  public SpecDiff diff(@Nullable ApiModel before, ApiModel after) {
    Objects.requireNonNull(after, "after");
    var afterOps = indexById(after);
    if (before == null) {
      return new SpecDiff(
          afterOps.values().stream().map(SpecDiffer::ref).toList(), List.of(), List.of());
    }
    var beforeOps = indexById(before);

    var added = new ArrayList<OpRef>();
    for (var op : afterOps.values()) {
      if (!beforeOps.containsKey(op.operationId())) {
        added.add(ref(op));
      }
    }
    var removed = new ArrayList<OpRef>();
    for (var op : beforeOps.values()) {
      if (!afterOps.containsKey(op.operationId())) {
        removed.add(ref(op));
      }
    }
    var modified = new ArrayList<OpChange>();
    for (var op : afterOps.values()) {
      var prev = beforeOps.get(op.operationId());
      if (prev == null) {
        continue;
      }
      var changes = OperationDiffer.diff(before, prev, after, op, schemaDiffer);
      if (!changes.isEmpty()) {
        modified.add(new OpChange(ref(op), changes));
      }
    }
    return new SpecDiff(added, removed, modified);
  }

  private static Map<String, Operation> indexById(ApiModel model) {
    var index = new LinkedHashMap<String, Operation>();
    for (var op : model.operations()) {
      index.putIfAbsent(op.operationId(), op);
    }
    return index;
  }

  private static OpRef ref(Operation op) {
    return new OpRef(op.operationId(), op.method(), op.path());
  }
}
