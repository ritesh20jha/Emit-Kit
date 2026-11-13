package dev.emitkit.gen.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.gen.core.golden.GoldenFiles;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.RequestBody;
import dev.emitkit.spec.model.Schema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpGeneratorTest {

  private final McpGenerator generator = new McpGenerator();

  @ParameterizedTest
  @ValueSource(strings = {"petstore", "polymorphism"})
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var m = FixtureSpecs.load("petstore");
    assertThat(generator.generate(m, ctx())).isEqualTo(generator.generate(m, ctx()));
  }

  @Test
  void idIsMCP() {
    assertThat(generator.id()).isEqualTo(GeneratorId.MCP);
  }

  @Test
  void everyInputSchemaIsValidJsonSchema() {
    var factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    var metaSchema = factory.getSchema(SchemaLocation.of(SchemaId.V202012));
    // The metaschema must reject a broken schema, or the check below proves nothing.
    assertThat(metaSchema.validate("{\"type\": \"nope\"}", InputFormat.JSON)).isNotEmpty();
    for (String fixture : FixtureSpecs.ALL) {
      for (var tool : McpGenerator.tools(FixtureSpecs.load(fixture))) {
        assertThat(metaSchema.validate(tool.inputSchemaJson(), InputFormat.JSON))
            .as("%s: %s", fixture, tool.name())
            .isEmpty();
      }
    }
  }

  @Test
  void toolNamesAreUniqueAndAtMost64() {
    for (String fixture : List.of("petstore", "polymorphism")) {
      List<String> names =
          McpGenerator.tools(FixtureSpecs.load(fixture)).stream()
              .map(McpGenerator.Tool::name)
              .toList();
      assertThat(names).doesNotHaveDuplicates();
      assertThat(names).allSatisfy(name -> assertThat(name).hasSizeLessThanOrEqualTo(64));
    }
  }

  @Test
  void collidingAndTruncatedNamesGetDeterministicSuffixes() {
    String long70 = "a".repeat(70);
    var model =
        model(
            List.of(
                operation("getThing", "/a"),
                operation("get-thing", "/b"),
                operation(long70 + "b", "/c"),
                operation(long70 + "c", "/d")),
            Map.of());
    assertThat(McpGenerator.tools(model))
        .extracting(McpGenerator.Tool::name)
        .containsExactly("get_thing", "get_thing_2", "a".repeat(64), "a".repeat(62) + "_2");
  }

  @Test
  void recursiveRefRendersEmptySchemaAtTheSecondVisit() {
    var node =
        new ObjectSchema(
            Map.of("child", new RefSchema("Node", false, null)), Set.of(), null, false, null);
    var body = new RequestBody(true, "application/json", new RefSchema("Node", false, null));
    var model =
        model(List.of(withBody(operation("createNode", "/nodes"), body)), Map.of("Node", node));
    assertThat(McpGenerator.tools(model).getFirst().inputSchemaJson()).contains("\"child\": {}");
  }

  @Test
  void integerEnumsKeepNumericValues() {
    var status = new EnumSchema(PrimitiveType.INTEGER, List.of("1", "2"), true, null);
    var param = new Parameter("status", ParamLocation.QUERY, false, status, null);
    var op =
        new Operation(
            "listThings",
            false,
            "GET",
            "/things",
            "default",
            null,
            null,
            List.of(param),
            null,
            Map.of(),
            List.of(),
            false);
    String json = McpGenerator.tools(model(List.of(op), Map.of())).getFirst().inputSchemaJson();
    assertThat(json.replaceAll("\\s", "")).contains("\"enum\":[1,2,null]");
  }

  private static GeneratorContext ctx() {
    return new GeneratorContext(
        ".emitkit/",
        "1.2.3",
        new PackageNames("acme", "petstore-mcp", "petstore", "dev.acme", "petstore"),
        List.of(),
        Optional.empty());
  }

  private static ApiModel model(List<Operation> operations, Map<String, Schema> schemas) {
    return new ApiModel(
        new Info("Test", "1.0.0", null, null), List.of(), Map.of(), List.of(), operations, schemas);
  }

  private static Operation operation(String operationId, String path) {
    return new Operation(
        operationId,
        false,
        "GET",
        path,
        "default",
        null,
        null,
        List.of(),
        null,
        Map.of(),
        List.of(),
        false);
  }

  private static Operation withBody(Operation op, RequestBody body) {
    return new Operation(
        op.operationId(),
        false,
        "POST",
        op.path(),
        op.tag(),
        null,
        null,
        List.of(),
        body,
        Map.of(),
        List.of(),
        false);
  }
}
