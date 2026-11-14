package dev.emitkit.gen.typescriptsdk;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.naming.Language;
import dev.emitkit.spec.naming.NameStrategy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Exported TypeScript names for schemas. A schema whose name collides with a global the generated
 * code uses, or with a name the SDK's own modules export, gets a {@code Model} suffix. Any other
 * collision gets 2, 3, ... appended, in model order.
 */
final class TsNames {

  private static final NameStrategy NAMES = NameStrategy.forLanguage(Language.TYPESCRIPT);

  /** Globals the generated code uses, plus names client.ts and errors.ts export. */
  private static final Set<String> SHADOWED =
      Set.of(
          ("Error Response Request Headers Record Promise Array Date Map Set Object String "
                  + "Number Boolean Function Symbol Partial Readonly Omit Pick AuthOptions "
                  + "RequestOptions Requester ApiError BadRequestError UnauthorizedError "
                  + "ForbiddenError NotFoundError ConflictError RateLimitError ServerError")
              .split(" "));

  private final Map<String, String> schemaTypes;

  private TsNames(Map<String, String> schemaTypes) {
    this.schemaTypes = schemaTypes;
  }

  /**
   * Names for every schema in {@code model}. Resource class names and {@code clientName} are
   * reserved first, so no schema can take the name of an export from another module.
   */
  static TsNames of(ApiModel model, String clientName) {
    Set<String> taken = new HashSet<>(SHADOWED);
    taken.add(clientName);
    for (var op : model.operations()) {
      taken.add(resourceClass(op.tag()));
    }
    Map<String, String> types = new LinkedHashMap<>();
    for (String raw : model.schemas().keySet()) {
      String base = base(raw, clientName);
      String name = base;
      for (int n = 2; taken.contains(name); n++) {
        name = base + n;
      }
      taken.add(name);
      types.put(raw, name);
    }
    return new TsNames(types);
  }

  /** The exported type name of the schema called {@code raw}. */
  String typeName(String raw) {
    String name = schemaTypes.get(raw);
    return name != null ? name : base(raw, "");
  }

  /** The exported class name of the resource for {@code tag}. */
  static String resourceClass(String tag) {
    return NAMES.type(tag) + "Resource";
  }

  /** The file stem of the resource for {@code tag}, under {@code resources/}. */
  static String resourceFile(String tag) {
    return NAMES.file(tag);
  }

  /** The client property that holds the resource for {@code tag}. */
  static String resourceMember(String tag) {
    return NAMES.member(tag);
  }

  /** A method name for an operation, escaped so it cannot clash with the resource class. */
  static String methodName(String operationId) {
    String name = NAMES.member(operationId);
    return name.equals("client") || name.equals("constructor") ? name + "_" : name;
  }

  /** The Pascal-case base of a schema name, with the Model suffix where the name is shadowed. */
  private static String base(String raw, String clientName) {
    String type = NAMES.type(raw);
    if (type.endsWith("_")) {
      type = type.substring(0, type.length() - 1);
    }
    return SHADOWED.contains(type) || type.equals(clientName) ? type + "Model" : type;
  }
}
