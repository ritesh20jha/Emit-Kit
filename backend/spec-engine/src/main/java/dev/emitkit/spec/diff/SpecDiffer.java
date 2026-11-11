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
 * Compares two API models. Operations match by operationId. If the method or the path differs
 * (template variable names ignored, so {@code /pets/{id}} and {@code /pets/{petId}} are the same),
 * the operation is reported as removed (old ref) and added (new ref), not modified. When a model
 * repeats an operationId, the first occurrence wins.
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

    // A moved operation (method or path changed) appears in both lists, each in its own model's
    // order.
    var added = new ArrayList<OpRef>();
    for (var op : afterOps.values()) {
      var prev = beforeOps.get(op.operationId());
      if (prev == null || isMoved(prev, op)) {
        added.add(ref(op));
      }
    }
    var removed = new ArrayList<OpRef>();
    for (var op : beforeOps.values()) {
      var next = afterOps.get(op.operationId());
      if (next == null || isMoved(op, next)) {
        removed.add(ref(op));
      }
    }
    var modified = new ArrayList<OpChange>();
    for (var op : afterOps.values()) {
      var prev = beforeOps.get(op.operationId());
      if (prev == null || isMoved(prev, op)) {
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

  /**
   * True when the method differs, or the path differs after template variable names are dropped.
   */
  private static boolean isMoved(Operation before, Operation after) {
    return !Objects.equals(before.method(), after.method())
        || !normalizePath(before.path()).equals(normalizePath(after.path()));
  }

  private static String normalizePath(String path) {
    return path.replaceAll("\\{[^}]*\\}", "{}");
  }

  private static OpRef ref(Operation op) {
    return new OpRef(op.operationId(), op.method(), op.path());
  }
}
