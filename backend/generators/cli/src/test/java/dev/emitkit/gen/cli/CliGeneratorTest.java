package dev.emitkit.gen.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.gen.core.golden.GoldenFiles;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CliGeneratorTest {

  private static final Pattern FROM_CLAUSE = Pattern.compile("from \"([^\"]+)\"");

  private final CliGenerator generator = new CliGenerator();

  @ParameterizedTest
  @ValueSource(strings = {"petstore", "multi-tag", "reserved-words"})
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var model = FixtureSpecs.load("petstore");
    assertThat(generator.generate(model, ctx())).isEqualTo(generator.generate(model, ctx()));
  }

  @Test
  void idIsCLI() {
    assertThat(generator.id()).isEqualTo(GeneratorId.CLI);
  }

  @Test
  void requiredParamIsRequiredOption() {
    assertThat(source(generate("petstore"), "src/commands/pets.ts"))
        .contains(".requiredOption(\"--pet-id <value>\"");
  }

  @Test
  void tagsThatCollideAfterKebabCaseGetDistinctFiles() {
    // "user" and "User" both kebab-case to "user". The first tag to appear keeps the name.
    var files = generate("reserved-words");
    assertThat(paths(files))
        .contains(
            ".emitkit/cli/src/commands/user.ts",
            ".emitkit/cli/src/commands/user-2.ts",
            ".emitkit/cli/src/commands/default.ts");
    assertThat(source(files, "src/commands/user-2.ts"))
        .contains(".command(\"user-2\")")
        .contains(".command(\"import\")");
  }

  @Test
  void reservedWordOperationIdsBecomeStringLiterals() {
    var files = generate("reserved-words");
    assertThat(source(files, "src/commands/default.ts"))
        .contains(".command(\"delete\")")
        .contains(".command(\"default\")");
    assertThat(source(files, "src/commands/user.ts"))
        .contains(".command(\"class\")")
        .contains(".command(\"get-item-in\")")
        .contains(".requiredOption(\"--in <value>\"")
        .contains("encodeURIComponent(String(opts[\"in\"]))");
  }

  @Test
  void pathParametersAreUrlEncoded() {
    assertThat(source(generate("petstore"), "src/commands/pets.ts"))
        .contains("path: \"/pets/\" + encodeURIComponent(String(opts[\"petId\"])),");
  }

  @Test
  void paramsNamedLikeGlobalOptionsGetSuffixedFlags() {
    var op =
        operation(
            "search",
            "Search",
            param("token", ParamLocation.QUERY, false),
            param("json", ParamLocation.QUERY, false));
    assertThat(source(generate(model(op)), "src/commands/things.ts"))
        .contains(".option(\"--token-2 <value>\"")
        .contains(".option(\"--json-2 <value>\"")
        .contains("\"token\": opts[\"token2\"],")
        .contains("\"json\": opts[\"json2\"],");
  }

  @Test
  void versionParamDoesNotCollideWithCommanderVersionFlag() {
    // Commander's program-level -V, --version would consume a subcommand's --version value.
    var op = operation("search", "Search", param("version", ParamLocation.QUERY, false));
    assertThat(source(generate(model(op)), "src/commands/things.ts"))
        .contains(".option(\"--version-2 <value>\"")
        .contains("\"version\": opts[\"version2\"],")
        .doesNotContain("--version <value>");
  }

  @Test
  void specStringsCannotBreakOutOfTheStringLiteral() {
    var op = operation("getThing", "Find \"thing\" \\ done\nnow */");
    assertThat(source(generate(model(op)), "src/commands/things.ts"))
        .contains(".description(\"Find \\\"thing\\\" \\\\ done now */\")");
  }

  @Test
  void onlyCommanderAndNodeBuiltinsAreImported() {
    for (String fixture : List.of("petstore", "reserved-words")) {
      for (GeneratedFile file : generate(fixture)) {
        Matcher matcher = FROM_CLAUSE.matcher(file.content());
        while (matcher.find()) {
          String module = matcher.group(1);
          assertThat(
                  module.startsWith(".") || module.equals("commander") || module.equals("node:fs"))
              .as("%s imports %s", file.path(), module)
              .isTrue();
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {".emitkit/", ".emitkit", ".emitkit//"})
  void outputDirIsNormalisedToOneTrailingSlash(String outputDir) {
    assertThat(generator.generate(FixtureSpecs.load("petstore"), ctx(outputDir)))
        .extracting(GeneratedFile::path)
        .allSatisfy(path -> assertThat(path).startsWith(".emitkit/cli/"))
        .contains(".emitkit/cli/src/index.ts");
  }

  @Test
  void binIsTheUnscopedNpmNameAndCommanderIsTheOnlyDependency() {
    assertThat(source(generate("petstore"), "package.json"))
        .contains("\"name\": \"@acme/petstore-cli\",")
        .contains("\"petstore-cli\": \"dist/index.js\"")
        .contains("\"commander\": \"^11\"");
  }

  private static GeneratorContext ctx() {
    return ctx(".emitkit/");
  }

  private static GeneratorContext ctx(String outputDir) {
    var names = new PackageNames("acme", "petstore-cli", "petstore", "dev.acme", "petstore");
    return new GeneratorContext(outputDir, "1.2.3", names, List.of("typescript"), Optional.empty());
  }

  /** Generated files for a fixture, under the default output directory. */
  private List<GeneratedFile> generate(String fixture) {
    return generator.generate(FixtureSpecs.load(fixture), ctx());
  }

  private List<GeneratedFile> generate(ApiModel model) {
    return generator.generate(model, ctx());
  }

  private static List<String> paths(List<GeneratedFile> files) {
    return files.stream().map(GeneratedFile::path).toList();
  }

  /** Content of a file under {@code .emitkit/cli/}, addressed relative to that root. */
  private static String source(List<GeneratedFile> files, String relative) {
    String path = ".emitkit/cli/" + relative;
    return files.stream()
        .filter(f -> f.path().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no generated file " + path))
        .content();
  }

  /** Petstore's schemes and no servers, with the given operations and no declared tags. */
  private static ApiModel model(Operation... operations) {
    ApiModel base = FixtureSpecs.load("petstore");
    return new ApiModel(
        new Info("Things", "1.0.0", null, null),
        List.of(),
        base.securitySchemes(),
        List.of(),
        List.of(operations),
        Map.of());
  }

  private static Operation operation(String id, String summary, Parameter... params) {
    return new Operation(
        id,
        false,
        "get",
        "/things/{id}",
        "Things",
        summary,
        null,
        List.of(params),
        null,
        Map.of(),
        List.of(),
        false);
  }

  private static Parameter param(String name, ParamLocation in, boolean required) {
    return new Parameter(
        name, in, required, new PrimitiveSchema(PrimitiveType.STRING, null, false, null), null);
  }
}
