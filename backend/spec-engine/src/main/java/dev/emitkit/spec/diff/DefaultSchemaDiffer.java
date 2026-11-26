package dev.emitkit.spec.diff;

import static dev.emitkit.spec.diff.ChangeKind.ENUM_VALUE_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.ENUM_VALUE_REMOVED;
import static dev.emitkit.spec.diff.ChangeKind.REQUEST_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.REQUEST_REQUIRED_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.RESPONSE_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.RESPONSE_PROPERTY_REMOVED;
import static dev.emitkit.spec.diff.ChangeKind.TYPE_CHANGED;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Schema rules for request and response bodies and parameters. {@code $ref} is followed through
 * each side's own {@code schemas} map; a ref that cannot be resolved reports nothing. Each pair of
 * refs is compared at most once per top-level call, so recursive and densely shared schemas
 * terminate in time linear in the number of ref pairs. A shared schema changed under two locations
 * is therefore reported only at the first location reached.
 *
 * <p>Locations are property names joined by {@code "."} after the given location, e.g. {@code
 * "body.tag"} or {@code "200.owner.address.zip"}. Array items and {@code additionalProperties} keep
 * the location of their parent.
 *
 * <p>Request side: a required property added, or an existing property that became required, is
 * breaking; an optional property added is not. Removed request properties are not reported.
 * Response side: a property removed is breaking, an added one is not. Both sides: an enum value
 * removed is breaking, an added one is not, and a change of schema kind or primitive type is
 * breaking.
 *
 * <p>Known limitations:
 *
 * <ul>
 *   <li>{@code allOf} and {@code oneOf} parts are compared pairwise by index, and only when both
 *       sides have the same kind and the same part count. An object refactored into {@code allOf},
 *       reordered parts, or a changed part count can give a false {@code TYPE_CHANGED} or miss a
 *       change. A follow-up is planned.
 *   <li>A response schema that appears or disappears on one side (a null side) reports nothing.
 * </ul>
 */
public final class DefaultSchemaDiffer implements SchemaDiffer {

  @Override
  public List<Change> diffRequest(
      ApiModel before,
      @Nullable Schema beforeSchema,
      ApiModel after,
      @Nullable Schema afterSchema,
      String location) {
    return new Walk(before, after, true).run(beforeSchema, afterSchema, location);
  }

  @Override
  public List<Change> diffResponse(
      ApiModel before,
      @Nullable Schema beforeSchema,
      ApiModel after,
      @Nullable Schema afterSchema,
      String location) {
    return new Walk(before, after, false).run(beforeSchema, afterSchema, location);
  }

  /** One top-level comparison. Holds the ref pairs already visited and the side's rules. */
  private static final class Walk {

    /** A (before, after) pair of named schemas. */
    private record RefPair(String before, String after) {}

    /** A schema with its component name, or a null name when it was written inline. */
    private record Resolved(@Nullable String name, Schema schema) {}

    private final ApiModel before;
    private final ApiModel after;
    private final boolean request;
    private final Set<RefPair> visited = new HashSet<>();
    private final List<Change> changes = new ArrayList<>();

    Walk(ApiModel before, ApiModel after, boolean request) {
      this.before = before;
      this.after = after;
      this.request = request;
    }

    List<Change> run(@Nullable Schema beforeSchema, @Nullable Schema afterSchema, String location) {
      compare(beforeSchema, afterSchema, location);
      return changes;
    }

    private void compare(@Nullable Schema beforeSchema, @Nullable Schema afterSchema, String loc) {
      if (beforeSchema == null || afterSchema == null) {
        return;
      }
      var b = resolve(before, beforeSchema);
      var a = resolve(after, afterSchema);
      if (b == null || a == null) {
        return;
      }
      if (b.name() == null || a.name() == null) {
        compareResolved(b.schema(), a.schema(), loc);
        return;
      }
      if (!visited.add(new RefPair(b.name(), a.name()))) {
        return;
      }
      compareResolved(b.schema(), a.schema(), loc);
    }

    private void compareResolved(Schema b, Schema a, String location) {
      if (b.getClass() != a.getClass() || !Objects.equals(primitiveType(b), primitiveType(a))) {
        changes.add(new Change(TYPE_CHANGED, location, true));
        return;
      }
      if (b instanceof ObjectSchema bo && a instanceof ObjectSchema ao) {
        compareObjects(bo, ao, location);
      } else if (b instanceof ArraySchema ba && a instanceof ArraySchema aa) {
        compare(ba.items(), aa.items(), location);
      } else if (b instanceof EnumSchema be && a instanceof EnumSchema ae) {
        compareEnums(be, ae, location);
      } else if (b instanceof OneOfSchema bo && a instanceof OneOfSchema ao) {
        compareParts(bo.options(), ao.options(), location);
      } else if (b instanceof AllOfSchema bl && a instanceof AllOfSchema al) {
        compareParts(bl.parts(), al.parts(), location);
      }
    }

    /**
     * Properties in after order: shared ones are compared, added ones reported, and on the request
     * side a shared property that became required is reported. Then response-side removals in
     * before order. Finally {@code additionalProperties} at the same location.
     */
    private void compareObjects(ObjectSchema b, ObjectSchema a, String location) {
      for (var entry : a.properties().entrySet()) {
        var name = entry.getKey();
        var propertyLocation = location + "." + name;
        if (b.properties().containsKey(name)) {
          if (request && !b.required().contains(name) && a.required().contains(name)) {
            changes.add(new Change(REQUEST_REQUIRED_PROPERTY_ADDED, propertyLocation, true));
          }
          compare(b.properties().get(name), entry.getValue(), propertyLocation);
        } else if (!request) {
          changes.add(new Change(RESPONSE_PROPERTY_ADDED, propertyLocation, false));
        } else if (a.required().contains(name)) {
          changes.add(new Change(REQUEST_REQUIRED_PROPERTY_ADDED, propertyLocation, true));
        } else {
          changes.add(new Change(REQUEST_PROPERTY_ADDED, propertyLocation, false));
        }
      }
      if (!request) {
        for (var name : b.properties().keySet()) {
          if (!a.properties().containsKey(name)) {
            changes.add(new Change(RESPONSE_PROPERTY_REMOVED, location + "." + name, true));
          }
        }
      }
      compare(b.additionalProperties(), a.additionalProperties(), location);
    }

    /** One entry per enum per direction: the change carries no value, so repeats would be noise. */
    private void compareEnums(EnumSchema b, EnumSchema a, String location) {
      if (b.values().stream().anyMatch(v -> !a.values().contains(v))) {
        changes.add(new Change(ENUM_VALUE_REMOVED, location, true));
      }
      if (a.values().stream().anyMatch(v -> !b.values().contains(v))) {
        changes.add(new Change(ENUM_VALUE_ADDED, location, false));
      }
    }

    /** Pairs by index when both lists have the same length; otherwise nothing beyond the kind. */
    private void compareParts(List<Schema> b, List<Schema> a, String location) {
      if (b.size() != a.size()) {
        return;
      }
      for (int i = 0; i < b.size(); i++) {
        compare(b.get(i), a.get(i), location);
      }
    }

    /**
     * Follows a chain of refs. Returns null when a name is missing or the chain loops, so the
     * caller reports nothing for it.
     */
    private static @Nullable Resolved resolve(ApiModel model, Schema schema) {
      @Nullable String name = null;
      @Nullable Schema current = schema;
      var seen = new HashSet<String>();
      while (current instanceof RefSchema ref) {
        if (!seen.add(ref.name())) {
          return null;
        }
        name = ref.name();
        current = model.schemas().get(name);
      }
      return current == null ? null : new Resolved(name, current);
    }

    private static @Nullable PrimitiveType primitiveType(Schema schema) {
      if (schema instanceof PrimitiveSchema p) {
        return p.type();
      }
      if (schema instanceof EnumSchema e) {
        return e.type();
      }
      return null;
    }
  }
}
