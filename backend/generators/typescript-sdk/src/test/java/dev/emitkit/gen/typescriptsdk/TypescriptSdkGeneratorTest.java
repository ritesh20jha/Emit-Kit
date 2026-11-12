package dev.emitkit.gen.typescriptsdk;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.gen.core.FileKind;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.gen.core.fixtures.FixtureSpecs;
import dev.emitkit.gen.core.golden.GoldenFiles;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypescriptSdkGeneratorTest {

  private final TypeScriptSdkGenerator generator = new TypeScriptSdkGenerator();

  @ParameterizedTest
  @ValueSource(strings = {"petstore", "auth-schemes"})
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var model = FixtureSpecs.load("auth-schemes");
    assertThat(generator.generate(model, ctx())).isEqualTo(generator.generate(model, ctx()));
  }

  @Test
  void idIsTS_SDK() {
    assertThat(generator.id()).isEqualTo(GeneratorId.TS_SDK);
  }

  @Test
  void customStubIsMarked() {
    assertThat(generate("petstore", ".emitkit/", names(null, "petstore-sdk")))
        .filteredOn(f -> f.path().endsWith("src/custom/index.ts"))
        .singleElement()
        .extracting(GeneratedFile::kind)
        .isEqualTo(FileKind.CUSTOM_STUB);
  }

  @Test
  void unscopedPackageName() {
    assertThat(packageJson(names(null, "petstore-sdk")))
        .contains("\"name\": \"petstore-sdk\",")
        .doesNotContain("@");
  }

  @Test
  void scopedPackageName() {
    assertThat(packageJson(names("acme", "petstore-sdk")))
        .contains("\"name\": \"@acme/petstore-sdk\",");
  }

  @ParameterizedTest
  @ValueSource(strings = {".emitkit/", ".emitkit", ".emitkit//"})
  void outputDirIsNormalisedToOneTrailingSlash(String outputDir) {
    assertThat(generate("petstore", outputDir, names(null, "petstore-sdk")))
        .extracting(GeneratedFile::path)
        .allSatisfy(path -> assertThat(path).startsWith(".emitkit/sdk/typescript/"))
        .contains(".emitkit/sdk/typescript/src/client.ts");
  }

  @Test
  void emptyOutputDirWritesUnderSdkRoot() {
    assertThat(generate("petstore", "", names(null, "petstore-sdk")))
        .extracting(GeneratedFile::path)
        .contains("sdk/typescript/src/client.ts");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Petstore", "Petstore Client"})
  void clientNameEndsWithClientSuffixOnce(String title) {
    assertThat(clientSource(title, List.of())).contains("export class PetstoreClient {");
  }

  @Test
  void apiKeyIsSentInHeaderOnlyWhenAHeaderSchemeExists() {
    // auth-schemes declares both a header and a query apiKey scheme.
    String client =
        content(
            generate("auth-schemes", ".emitkit/", names(null, "petstore-sdk")), "src/client.ts");
    assertThat(client)
        .contains("this.authHeaders[\"X-API-Key\"] = opts.apiKey;")
        .doesNotContain("api_key");
  }

  @Test
  void apiKeyFallsBackToTheQueryParameterWithoutAHeaderScheme() {
    var queryKey =
        new SecurityScheme("queryKey", SecurityScheme.Kind.API_KEY, "query", "api_key", null);
    assertThat(clientSource(Map.of("queryKey", queryKey)))
        .contains("this.authQuery[\"api_key\"] = opts.apiKey;")
        .doesNotContain("this.authHeaders[\"");
  }

  @Test
  void titleCannotCloseTheDocComment() {
    assertThat(clientSource("Pets */ Store", List.of()))
        .contains("/** Client for the Pets *\\/ Store API. */")
        .contains("export class PetsStoreClient {");
  }

  @Test
  void noServersMeansTheConstructorRequiresBaseUrl() {
    assertThat(clientSource("Petstore", List.of()))
        .contains("const DEFAULT_BASE_URL = \"\";")
        .contains("baseUrl is required because the API declares no servers");
  }

  @Test
  void firstAbsoluteServerIsTheDefaultBaseUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("const DEFAULT_BASE_URL = \"https://api.example.com/v1\";");
  }

  @Test
  void relativeServerIsNotADefaultBaseUrl() {
    // The parser reports a relative "/" server when the document declares none.
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains("const DEFAULT_BASE_URL = \"\";");
  }

  private String packageJson(PackageNames names) {
    return content(generate("petstore", ".emitkit/", names), "package.json");
  }

  private String clientSource(String title, List<Server> servers) {
    ApiModel base = FixtureSpecs.load("petstore");
    ApiModel model =
        new ApiModel(
            new Info(title, "1.0.0", null, null),
            servers,
            base.securitySchemes(),
            base.tags(),
            base.operations(),
            base.schemas());
    return content(generate(model, ".emitkit/", names(null, "petstore-sdk")), "src/client.ts");
  }

  private String clientSource(Map<String, SecurityScheme> schemes) {
    ApiModel base = FixtureSpecs.load("petstore");
    ApiModel model =
        new ApiModel(
            base.info(), base.servers(), schemes, base.tags(), base.operations(), base.schemas());
    return content(generate(model, ".emitkit/", names(null, "petstore-sdk")), "src/client.ts");
  }

  private static String content(List<GeneratedFile> files, String relative) {
    return files.stream()
        .filter(f -> f.path().equals(".emitkit/sdk/typescript/" + relative))
        .findFirst()
        .orElseThrow()
        .content();
  }

  private List<GeneratedFile> generate(String fixture, String outputDir, PackageNames names) {
    return generate(FixtureSpecs.load(fixture), outputDir, names);
  }

  private List<GeneratedFile> generate(ApiModel model, String outputDir, PackageNames names) {
    return generator.generate(
        model,
        new GeneratorContext(outputDir, "1.2.3", names, List.of("typescript"), Optional.empty()));
  }

  private GeneratorContext ctx() {
    return new GeneratorContext(
        ".emitkit/",
        "1.2.3",
        names("acme", "petstore-sdk"),
        List.of("typescript"),
        Optional.empty());
  }

  private static PackageNames names(String scope, String npmName) {
    return new PackageNames(scope, npmName, "petstore", "dev.acme", "petstore");
  }
}
