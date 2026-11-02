package dev.emitkit.spec.parse;

import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/** Maps every path/method pair of a parsed document to a model {@link Operation}. */
final class OperationMapper {

  private static final String JSON = "application/json";
  private static final Pattern PATH_PARAM = Pattern.compile("\\{([^}]*)}");

  private OperationMapper() {}

  static List<Operation> map(OpenAPI api) {
    var operations = new ArrayList<Operation>();
    if (api.getPaths() == null) {
      return operations;
    }
    for (var pathEntry : api.getPaths().entrySet()) {
      PathItem item = pathEntry.getValue();
      for (var methodEntry : operationsOf(item).entrySet()) {
        var op = methodEntry.getValue();
        if (op != null) {
          operations.add(map(api, methodEntry.getKey(), pathEntry.getKey(), item, op));
        }
      }
    }
    return operations;
  }

  // Methods in the order the model documents: get, put, post, delete, options, head, patch, trace.
  private static Map<String, io.swagger.v3.oas.models.Operation> operationsOf(PathItem item) {
    var ops = new LinkedHashMap<String, io.swagger.v3.oas.models.Operation>();
    ops.put("GET", item.getGet());
    ops.put("PUT", item.getPut());
    ops.put("POST", item.getPost());
    ops.put("DELETE", item.getDelete());
    ops.put("OPTIONS", item.getOptions());
    ops.put("HEAD", item.getHead());
    ops.put("PATCH", item.getPatch());
    ops.put("TRACE", item.getTrace());
    return ops;
  }

  private static Operation map(
      OpenAPI api,
      String method,
      String path,
      PathItem item,
      io.swagger.v3.oas.models.Operation op) {
    boolean synthesized = op.getOperationId() == null || op.getOperationId().isBlank();
    String operationId = synthesized ? synthesizeId(method, path) : op.getOperationId();
    String tag = op.getTags() == null || op.getTags().isEmpty() ? "default" : op.getTags().get(0);
    var security = op.getSecurity() != null ? op.getSecurity() : api.getSecurity();
    return new Operation(
        operationId,
        synthesized,
        method,
        path,
        tag,
        op.getSummary(),
        op.getDescription(),
        parameters(item, op),
        requestBody(resolveBody(api, op.getRequestBody())),
        responses(api, op.getResponses()),
        securityNames(security),
        Boolean.TRUE.equals(op.getDeprecated()));
  }

  /** {@code GET /users/{id}} becomes {@code getUsersById}. */
  private static String synthesizeId(String method, String path) {
    String expanded =
        PATH_PARAM.matcher(path).replaceAll(m -> " By" + capitalize(m.group(1)) + " ");
    var id = new StringBuilder(method.toLowerCase(Locale.ROOT));
    for (String token : expanded.split("[^A-Za-z0-9]+")) {
      if (!token.isEmpty()) {
        id.append(capitalize(token));
      }
    }
    return id.toString();
  }

  private static String capitalize(String s) {
    return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  // Path-level parameters first, then operation-level, in document order.
  private static List<Parameter> parameters(PathItem item, io.swagger.v3.oas.models.Operation op) {
    var params = new ArrayList<Parameter>();
    addParameters(params, item.getParameters());
    addParameters(params, op.getParameters());
    return params;
  }

  private static void addParameters(
      List<Parameter> out, @Nullable List<io.swagger.v3.oas.models.parameters.Parameter> source) {
    if (source == null) {
      return;
    }
    for (var p : source) {
      var in = location(p.getIn());
      if (in != null) {
        out.add(
            new Parameter(
                p.getName(),
                in,
                Boolean.TRUE.equals(p.getRequired()),
                SchemaMapper.map(p.getSchema()),
                p.getDescription()));
      }
    }
  }

  private static @Nullable ParamLocation location(@Nullable String in) {
    if (in == null) {
      return null;
    }
    return switch (in) {
      case "path" -> ParamLocation.PATH;
      case "query" -> ParamLocation.QUERY;
      case "header" -> ParamLocation.HEADER;
      case "cookie" -> ParamLocation.COOKIE;
      default -> null;
    };
  }

  private static @Nullable RequestBody requestBody(
      io.swagger.v3.oas.models.parameters.@Nullable RequestBody body) {
    if (body == null || body.getContent() == null || body.getContent().isEmpty()) {
      return null;
    }
    String contentType = preferredContentType(body.getContent());
    return new RequestBody(
        Boolean.TRUE.equals(body.getRequired()),
        contentType,
        SchemaMapper.map(body.getContent().get(contentType).getSchema()));
  }

  // The parser leaves component refs for request bodies and responses unresolved (resolveFully is
  // off, so schema refs stay named). Look them up here; an unknown ref keeps the original object.
  private static io.swagger.v3.oas.models.parameters.@Nullable RequestBody resolveBody(
      OpenAPI api, io.swagger.v3.oas.models.parameters.@Nullable RequestBody body) {
    if (body == null || body.get$ref() == null || api.getComponents() == null) {
      return body;
    }
    var bodies = api.getComponents().getRequestBodies();
    return bodies == null ? body : bodies.getOrDefault(componentName(body.get$ref()), body);
  }

  private static ApiResponse resolveResponse(OpenAPI api, ApiResponse response) {
    if (response.get$ref() == null || api.getComponents() == null) {
      return response;
    }
    var responses = api.getComponents().getResponses();
    return responses == null
        ? response
        : responses.getOrDefault(componentName(response.get$ref()), response);
  }

  /** {@code #/components/responses/Ok} becomes {@code Ok}; other refs yield null. */
  private static @Nullable String componentName(String ref) {
    return ref.startsWith("#/components/") ? ref.substring(ref.lastIndexOf('/') + 1) : null;
  }

  private static Map<String, Response> responses(OpenAPI api, @Nullable ApiResponses responses) {
    var out = new LinkedHashMap<String, Response>();
    if (responses == null) {
      return out;
    }
    for (var entry : responses.entrySet()) {
      var response = resolveResponse(api, entry.getValue());
      out.put(
          entry.getKey(),
          new Response(entry.getKey(), response.getDescription(), responseSchema(response)));
    }
    return out;
  }

  private static @Nullable Schema responseSchema(ApiResponse response) {
    Content content = response.getContent();
    if (content == null || content.isEmpty()) {
      return null;
    }
    MediaType media = content.get(preferredContentType(content));
    return SchemaMapper.map(media.getSchema());
  }

  /** The first content type, preferring {@code application/json} when it is offered. */
  private static String preferredContentType(Content content) {
    return content.containsKey(JSON) ? JSON : content.keySet().iterator().next();
  }

  /** Scheme names only; scopes are not part of the model. */
  private static List<String> securityNames(@Nullable List<SecurityRequirement> requirements) {
    var names = new LinkedHashSet<String>();
    if (requirements != null) {
      for (var requirement : requirements) {
        names.addAll(requirement.keySet());
      }
    }
    return List.copyOf(names);
  }
}
