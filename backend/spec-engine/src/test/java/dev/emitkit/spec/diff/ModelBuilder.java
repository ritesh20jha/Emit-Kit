package dev.emitkit.spec.diff;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds models in code for diff tests. Every {@code with*} method returns a modified copy. */
final class ModelBuilder {

  private ModelBuilder() {}

  record Op(
      String operationId,
      String method,
      String path,
      @Nullable String summary,
      @Nullable String description,
      List<Parameter> parameters,
      @Nullable RequestBody requestBody,
      Map<String, Response> responses) {

    Op withSummary(String value) {
      return new Op(
          operationId, method, path, value, description, parameters, requestBody, responses);
    }

    Op withDescription(String value) {
      return new Op(operationId, method, path, summary, value, parameters, requestBody, responses);
    }

    Op withParam(Parameter param) {
      var params = new ArrayList<>(parameters);
      params.add(param);
      return new Op(
          operationId, method, path, summary, description, params, requestBody, responses);
    }

    Op withBody(RequestBody body) {
      return new Op(operationId, method, path, summary, description, parameters, body, responses);
    }

    Op withResponse(String status, @Nullable Schema schema) {
      var all = new LinkedHashMap<>(responses);
      all.put(status, new Response(status, null, schema));
      return new Op(operationId, method, path, summary, description, parameters, requestBody, all);
    }

    Operation toOperation() {
      return new Operation(
          operationId,
          false,
          method,
          path,
          "pets",
          summary,
          description,
          parameters,
          requestBody,
          responses,
          List.of(),
          false);
    }
  }

  static Op op(String operationId, String method, String path) {
    return new Op(operationId, method, path, null, null, List.of(), null, Map.of());
  }

  static ApiModel model(Op... ops) {
    return model(Map.of(), ops);
  }

  static ApiModel model(Map<String, Schema> schemas, Op... ops) {
    return new ApiModel(
        new Info("Pets", "1", null, null),
        List.of(),
        Map.of(),
        List.of(),
        Arrays.stream(ops).map(Op::toOperation).toList(),
        schemas);
  }

  static Schema string() {
    return new PrimitiveSchema(PrimitiveType.STRING, null, false, null);
  }

  static Schema integer() {
    return new PrimitiveSchema(PrimitiveType.INTEGER, null, false, null);
  }

  static Schema enumOf(String... values) {
    return new EnumSchema(PrimitiveType.STRING, List.of(values), false, null);
  }

  static Schema ref(String name) {
    return new RefSchema(name, false, null);
  }

  /** An object with the given properties; {@code required} names the required ones. */
  static Schema object(Map<String, Schema> properties, String... required) {
    return new ObjectSchema(properties, Set.of(required), null, false, null);
  }

  static Parameter query(String name, boolean required) {
    return query(name, required, null);
  }

  static Parameter query(String name, boolean required, @Nullable String description) {
    return new Parameter(name, ParamLocation.QUERY, required, string(), description);
  }

  static Parameter pathParam(String name) {
    return new Parameter(name, ParamLocation.PATH, true, string(), null);
  }

  static RequestBody jsonBody(boolean required) {
    return new RequestBody(required, "application/json", string());
  }

  static RequestBody jsonBody(Schema schema) {
    return new RequestBody(true, "application/json", schema);
  }
}
