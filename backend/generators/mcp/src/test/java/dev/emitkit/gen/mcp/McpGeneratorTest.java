package dev.emitkit.gen.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaId;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SpecVersion;
import dev.emitkit.gen.core.GeneratedFile;
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
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class McpGeneratorTest {

  private final McpGenerator generator = new McpGenerator();

  @ParameterizedTest
  @ValueSource(strings = {"petstore", "polymorphism", "bookshelf"})
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
  void toolDescriptionIsTheSummaryThenTheDescription() {
    var op =
        new Operation(
            "listBooks",
            false,
            "GET",
            "/books",
            "books",
            "List books",
            "Returns a page of books.\nNewest first.",
            List.of(),
            null,
            Map.of(),
            List.of(),
            false);
    assertThat(McpGenerator.tools(model(List.of(op), Map.of())).getFirst().description())
        .isEqualTo("List books\n\nReturns a page of books.\nNewest first.");
  }

  @Test
  void toolDescriptionIsTheDescriptionAloneWithoutASummary() {
    var op =
        new Operation(
            "listBooks",
            false,
            "GET",
            "/books",
            "books",
            null,
            "Returns a page of books.",
            List.of(),
            null,
            Map.of(),
            List.of(),
            false);
    assertThat(McpGenerator.tools(model(List.of(op), Map.of())).getFirst().description())
        .isEqualTo("Returns a page of books.");
  }

  @Test
  void toolDescriptionIsTheSummaryWhenTheDescriptionRepeatsIt() {
    var op =
        new Operation(
            "listBooks",
            false,
            "GET",
            "/books",
            "books",
            "List books",
            "List   books",
            List.of(),
            null,
            Map.of(),
            List.of(),
            false);
    assertThat(McpGenerator.tools(model(List.of(op), Map.of())).getFirst().description())
        .isEqualTo("List books");
  }

  @Test
  void serverDefaultsToTheFirstAbsoluteServerUrl() {
    String server = source(generate("bookshelf"), "src/server.ts");
    assertThat(server)
        .contains("const DEFAULT_BASE_URL: string = \"https://api.bookshelf.example.com/v1\";")
        .contains("const configured = process.env.API_BASE_URL ?? \"\";")
        .contains("return { baseUrl: DEFAULT_BASE_URL.replace(/\\/+$/, \"\") };");
  }

  @Test
  void serverWithNoUsableUrlExitsWithOneStderrLine() {
    // No servers and no API_BASE_URL: write one line to stderr, then exit 1. Nothing connects.
    var files =
        generator.generate(model(List.of(operation("listThings", "/things")), Map.of()), ctx());
    assertThat(source(files, "src/server.ts"))
        .contains("const DEFAULT_BASE_URL: string = \"\";")
        .contains("process.stderr.write(message, () => process.exit(1));")
        .contains("API_BASE_URL is required")
        .doesNotContain("throw new Error(\"API_BASE_URL");
  }

  @Test
  void serverRejectsABaseUrlThatIsNotAbsoluteHttp() {
    assertThat(source(generate("bookshelf"), "src/server.ts"))
        .contains("must be an absolute http(s) URL");
  }

  @Test
  void serverDoesNotExitWhenImported() {
    // The entry point runs only when this file is the process's entry; a plain import is inert.
    assertThat(source(generate("bookshelf"), "src/server.ts"))
        .contains("export async function main")
        .contains("process.argv[1]");
  }

  @Test
  void readmeStatesThatAlternativesSendEveryConfiguredCredential() {
    assertThat(source(generate("bookshelf"), "README.md"))
        .contains(
            "every configured credential named by any of the operation's alternative security"
                + " requirements is sent");
  }

  @Test
  void reservedHeaderParametersAreNotToolInputs() {
    var listItems =
        McpGenerator.tools(local("apikey-precedence")).stream()
            .filter(t -> t.name().equals("list_items"))
            .findFirst()
            .orElseThrow();
    assertThat(listItems.params())
        .extracting(McpGenerator.Tool.Param::name)
        .doesNotContain("Authorization", "Accept", "Content-Type")
        .contains("X-Api-Key");
    assertThat(listItems.inputSchemaJson())
        .doesNotContain("\"Authorization\"")
        .doesNotContain("\"Accept\"")
        .doesNotContain("\"Content-Type\"");
  }

  @Test
  void apiKeyPrecedenceSpecMatchesGolden() {
    GoldenFiles.assertMatches(
        "apikey-precedence", generator.generate(local("apikey-precedence"), ctx()));
  }

  /** A spec under this module's {@code src/test/resources/specs/}, parsed into the model. */
  private static ApiModel local(String name) {
    String text;
    try {
      text = Files.readString(Path.of("src", "test", "resources", "specs", name + ".yaml"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    ParseResult result = new SpecParser().parse(text);
    assertThat(result.errors()).isEmpty();
    return Objects.requireNonNull(result.model());
  }

  @Test
  void packageAndBinUseTheMcpSuffixSoTheyDoNotClashWithTheCliOrSdk() {
    String json = source(generate("petstore"), "package.json");
    assertThat(json)
        .contains("\"name\": \"@acme/petstore-mcp\",")
        .contains("\"petstore-mcp\": \"dist/server.js\"")
        .doesNotContain("\"@acme/petstore\"");
    // The server reports its name unchanged; only the package and bin carry the suffix.
    assertThat(source(generate("petstore"), "src/server.ts"))
        .contains("const SERVER_NAME = \"@acme/petstore\";");
    var unscoped =
        new GeneratorContext(
            ".emitkit/",
            "1.2.3",
            new PackageNames(null, "petstore", null, null, null),
            List.of(),
            Optional.empty());
    assertThat(source(generator.generate(FixtureSpecs.load("petstore"), unscoped), "package.json"))
        .contains("\"name\": \"petstore-mcp\",")
        .contains("\"petstore-mcp\": \"dist/server.js\"");
  }

  @Test
  void eachToolCarriesItsEffectiveSecurityRequirement() {
    String tools = source(generate("bookshelf"), "src/tools.ts");
    // listAuthors declares security: [], so its tool sends no credentials.
    assertThat(tools).contains("\"name\": \"list_authors\"").contains("\"security\": []");
    // createLoan accepts an API key or an OAuth2 token, not the global bearer token.
    assertThat(tools).contains("\"security\": [\n      \"apiKeyAuth\",\n      \"oauth2\"\n    ]");
  }

  @Test
  void serverMapsSecuritySchemesToCredentials() {
    // Json.write pretty-prints, so each pair spans lines; check the members of each entry.
    assertThat(source(generate("bookshelf"), "src/server.ts"))
        .contains("\"bearerAuth\",")
        .contains("\"apiKeyAuth\",")
        .contains("\"kind\": \"apiKey\"")
        .contains("\"in\": \"header\"")
        .contains("\"name\": \"X-Api-Key\"");
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

  private List<GeneratedFile> generate(String fixture) {
    return generator.generate(FixtureSpecs.load(fixture), ctx());
  }

  /** Content of a file under {@code .emitkit/mcp/}, addressed relative to that root. */
  private static String source(List<GeneratedFile> files, String relative) {
    String path = ".emitkit/mcp/" + relative;
    return files.stream()
        .filter(f -> f.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no generated file " + path))
        .content();
  }

  private static GeneratorContext ctx() {
    return new GeneratorContext(
        ".emitkit/",
        "1.2.3",
        new PackageNames("acme", "petstore", "petstore", "dev.acme", "petstore"),
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
