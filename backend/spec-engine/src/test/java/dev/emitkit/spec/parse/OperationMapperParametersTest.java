package dev.emitkit.spec.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Exercises the merge in {@link OperationMapper} directly, without the parser's pre-merge. */
class OperationMapperParametersTest {

  private static io.swagger.v3.oas.models.parameters.Parameter swaggerParam(
      String name, String in, boolean required) {
    return new io.swagger.v3.oas.models.parameters.Parameter().name(name).in(in).required(required);
  }

  /** Maps one {@code GET /users} with the given path-level and operation-level parameters. */
  private static List<Parameter> merged(
      List<io.swagger.v3.oas.models.parameters.Parameter> pathLevel,
      List<io.swagger.v3.oas.models.parameters.Parameter> opLevel) {
    var op = new io.swagger.v3.oas.models.Operation().operationId("getUsers").parameters(opLevel);
    var item = new PathItem().parameters(pathLevel).get(op);
    var api = new OpenAPI().paths(new Paths().addPathItem("/users", item));
    var ops = OperationMapper.map(api);
    assertThat(ops).hasSize(1);
    return ops.get(0).parameters();
  }

  @Test
  void operationLevelParamWinsAndPathOnlyParamIsKept() {
    var params =
        merged(
            List.of(swaggerParam("limit", "query", false), swaggerParam("trace", "header", false)),
            List.of(swaggerParam("limit", "query", true)));

    assertThat(params)
        .extracting(Parameter::name, Parameter::in, Parameter::required)
        .containsExactly(
            tuple("limit", ParamLocation.QUERY, true), tuple("trace", ParamLocation.HEADER, false));
  }

  @Test
  void sameNameInDifferentLocationIsNotMerged() {
    var params =
        merged(
            List.of(swaggerParam("limit", "query", false)),
            List.of(swaggerParam("limit", "header", true)));

    assertThat(params)
        .extracting(Parameter::in, Parameter::required)
        .containsExactly(tuple(ParamLocation.QUERY, false), tuple(ParamLocation.HEADER, true));
  }
}
