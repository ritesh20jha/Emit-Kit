package dev.emitkit.spec.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.spec.parse.Fixtures;
import dev.emitkit.spec.parse.SpecParser;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApiModelJsonTest {

  private final SpecParser parser = new SpecParser();

  @ParameterizedTest
  @ValueSource(strings = {"petstore-3.1.yaml", "schemas.yaml", "schemas-30.yaml"})
  void roundTrips(String file) {
    var r = parser.parse(Fixtures.read(file));
    assertThat(r.ok()).as("errors: %s", r.errors()).isTrue();
    var m = r.model();
    assertThat(ApiModelJson.fromJson(ApiModelJson.toJson(m))).isEqualTo(m);
  }

  @Test
  void schemaKindIsTagged() {
    var m = parser.parse(Fixtures.read("schemas.yaml")).model();
    assertThat(ApiModelJson.toJson(m)).contains("\"kind\":\"ref\"");
  }

  @Test
  void everySchemaKindIsTaggedByName() {
    var model = handMadeModel();
    var json = ApiModelJson.toJson(model);
    assertThat(json)
        .contains("\"kind\":\"object\"")
        .contains("\"kind\":\"array\"")
        .contains("\"kind\":\"primitive\"")
        .contains("\"kind\":\"enum\"")
        .contains("\"kind\":\"ref\"")
        .contains("\"kind\":\"oneOf\"")
        .contains("\"kind\":\"allOf\"");
  }

  @Test
  void handMadeModelRoundTripsWithNullsAndOrder() {
    var model = handMadeModel();
    var back = ApiModelJson.fromJson(ApiModelJson.toJson(model));
    assertThat(back).isEqualTo(model);
    assertThat(back.schemas().keySet())
        .containsExactly("Prim", "Arr", "Enm", "Ref", "One", "All", "Obj");
    var obj = (ObjectSchema) back.schemas().get("Obj");
    assertThat(obj.required()).containsExactly("z", "a");
    assertThat(obj.properties().keySet()).containsExactly("z", "a");
    assertThat(back.info().contactEmail()).isNull();
    assertThat(back.operations().get(0).summary()).isNull();
  }

  @Test
  void invalidJsonIsRejected() {
    assertThatThrownBy(() -> ApiModelJson.fromJson("{not json"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ApiModel handMadeModel() {
    var prim = new PrimitiveSchema(PrimitiveType.INTEGER, "int64", false, "an id");
    var ref = new RefSchema("Prim", true, "a reference");
    var props = new LinkedHashMap<String, Schema>();
    props.put("z", prim);
    props.put("a", ref);
    var schemas = new LinkedHashMap<String, Schema>();
    schemas.put("Prim", prim);
    schemas.put("Arr", new ArraySchema(prim, true, null));
    schemas.put("Enm", new EnumSchema(PrimitiveType.STRING, List.of("a", "b"), false, null));
    schemas.put("Ref", ref);
    schemas.put("One", new OneOfSchema(List.of(ref, prim), "kind", false, null));
    schemas.put("All", new AllOfSchema(List.of(ref), false, null));
    schemas.put(
        "Obj",
        new ObjectSchema(
            props, new LinkedHashSet<>(List.of("z", "a")), null, false, "ordered object"));
    var parameter =
        new Parameter(
            "status",
            ParamLocation.QUERY,
            false,
            new EnumSchema(PrimitiveType.STRING, List.of("open"), false, null),
            null);
    var operation =
        new Operation(
            "listPets",
            false,
            "GET",
            "/pets",
            "pets",
            null,
            null,
            List.of(parameter),
            null,
            Map.of("200", new Response("200", "ok", ref)),
            List.of("apiKey"),
            false);
    return new ApiModel(
        new Info("Pets", "1", null, null),
        List.of(new Server("https://example.test", null)),
        Map.of(
            "apiKey",
            new SecurityScheme("apiKey", SecurityScheme.Kind.API_KEY, "header", "X-Key", null)),
        List.of(new Tag("pets", null)),
        List.of(operation),
        schemas);
  }
}
