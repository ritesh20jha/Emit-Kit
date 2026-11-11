package dev.emitkit.spec.diff;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Compares one operation present on both sides. Changes are emitted in a fixed order: summary,
 * description, parameters in after order, removed parameters in before order, body, responses.
 */
final class OperationDiffer {

  private OperationDiffer() {}

  static List<Change> diff(
      ApiModel before, Operation b, ApiModel after, Operation a, SchemaDiffer schemas) {
    var changes = new ArrayList<Change>();
    if (!Objects.equals(b.summary(), a.summary())) {
      changes.add(new Change(ChangeKind.DESCRIPTION_CHANGED, "summary", false));
    }
    if (!Objects.equals(b.description(), a.description())) {
      changes.add(new Change(ChangeKind.DESCRIPTION_CHANGED, "description", false));
    }
    diffParams(before, b, after, a, schemas, changes);
    diffBody(before, b.requestBody(), after, a.requestBody(), schemas, changes);
    diffResponses(before, b, after, a, schemas, changes);
    return changes;
  }

  private static void diffParams(
      ApiModel before,
      Operation b,
      ApiModel after,
      Operation a,
      SchemaDiffer schemas,
      List<Change> changes) {
    var beforeParams = indexParams(b.parameters());
    var afterParams = indexParams(a.parameters());
    for (var ap : afterParams.values()) {
      var location = location(ap);
      var bp = beforeParams.get(location);
      if (bp == null) {
        changes.add(new Change(ChangeKind.PARAM_ADDED, location, ap.required()));
        continue;
      }
      if (!bp.required() && ap.required()) {
        changes.add(new Change(ChangeKind.PARAM_BECAME_REQUIRED, location, true));
      }
      if (bp.required() && !ap.required()) {
        changes.add(new Change(ChangeKind.PARAM_BECAME_OPTIONAL, location, false));
      }
      if (!Objects.equals(bp.description(), ap.description())) {
        changes.add(new Change(ChangeKind.DESCRIPTION_CHANGED, location, false));
      }
      changes.addAll(schemas.diffRequest(before, bp.schema(), after, ap.schema(), location));
    }
    for (var bp : beforeParams.values()) {
      var location = location(bp);
      if (!afterParams.containsKey(location)) {
        changes.add(new Change(ChangeKind.PARAM_REMOVED, location, true));
      }
    }
  }

  /** Only a newly required body is reported; an optional body that appears or disappears is not. */
  private static void diffBody(
      ApiModel before,
      @Nullable RequestBody bb,
      ApiModel after,
      @Nullable RequestBody ab,
      SchemaDiffer schemas,
      List<Change> changes) {
    boolean wasRequired = bb != null && bb.required();
    if (ab != null && ab.required() && !wasRequired) {
      changes.add(new Change(ChangeKind.BODY_BECAME_REQUIRED, "body", true));
    }
    if (bb != null && ab != null) {
      changes.addAll(schemas.diffRequest(before, bb.schema(), after, ab.schema(), "body"));
    }
  }

  private static void diffResponses(
      ApiModel before,
      Operation b,
      ApiModel after,
      Operation a,
      SchemaDiffer schemas,
      List<Change> changes) {
    for (var entry : a.responses().entrySet()) {
      var status = entry.getKey();
      var br = b.responses().get(status);
      if (br == null || (br.schema() == null && entry.getValue().schema() == null)) {
        continue;
      }
      changes.addAll(
          schemas.diffResponse(before, br.schema(), after, entry.getValue().schema(), status));
    }
  }

  /** Parameters keyed by location, which is unique per (in, name). First occurrence wins. */
  private static Map<String, Parameter> indexParams(List<Parameter> params) {
    var index = new LinkedHashMap<String, Parameter>();
    for (var p : params) {
      index.putIfAbsent(location(p), p);
    }
    return index;
  }

  /** Lowercase location, e.g. {@code "query.limit"}. */
  private static String location(Parameter p) {
    return p.in().name().toLowerCase(Locale.ROOT) + "." + p.name();
  }
}
