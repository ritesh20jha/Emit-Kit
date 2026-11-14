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
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TypescriptSdkGeneratorTest {

  private final TypeScriptSdkGenerator generator = new TypeScriptSdkGenerator();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "petstore",
        "auth-schemes",
        "polymorphism",
        "enums",
        "pagination",
        "no-operation-ids",
        "reserved-words",
        "deep-nesting",
        "multi-tag"
      })
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var model = FixtureSpecs.load("petstore");
    assertThat(generator.generate(model, ctx())).isEqualTo(generator.generate(model, ctx()));
  }

  @Test
  void idIsTS_SDK() {
    assertThat(generator.id()).isEqualTo(GeneratorId.TS_SDK);
  }

  @Test
  void reservedOperationIdIsEscaped() {
    assertThat(content("reserved-words", "src/resources/default.ts"))
        .contains("async delete_(")
        .contains("async default_(");
  }

  @Test
  void collidingTagsMergeIntoOneResource() {
    assertThat(paths("reserved-words")).filteredOn(p -> p.contains("resources/user")).hasSize(1);
    assertThat(content("reserved-words", "src/resources/user.ts"))
        .contains("export class UserResource {")
        .doesNotContain("export class UserResource2");
    assertThat(content("reserved-words", "src/client.ts"))
        .contains("readonly user: UserResource;")
        .contains("this.user = new UserResource(this);");
  }

  @Test
  void schemaNamesThatShadowGlobalsGetAModelSuffix() {
    assertThat(content("reserved-words", "src/types.ts"))
        .contains("export interface ErrorModel {")
        .contains("  \"2fa\"?: boolean;")
        .contains("export type StringModel = string;");
  }

  @Test
  void resourceMethodsParseTheSuccessBodyAndTypeTheParams() {
    assertThat(content("petstore", "src/resources/pets.ts"))
        .contains("export class PetsResource {")
        .contains(
            "async getPetById(params: { petId: number }): Promise<Pet> {\n"
                + "    const response = await this.client.request(\"GET\", "
                + "\"/pets/\" + encodeURIComponent(String(params.petId)));\n"
                + "    return response.json();\n")
        .contains("async listPets(params: { limit?: number } = {}): Promise<Pet[]> {")
        .contains("async createPet(body?: Pet): Promise<void> {");
  }

  @Test
  void enumsAndPolymorphismRenderAsUnionsAndIntersections() {
    assertThat(content("enums", "src/types.ts"))
        .contains("export type Color = \"red\" | \"green\" | \"blue\";")
        .contains("export type Priority = 1 | 2 | 3;");
    assertThat(content("polymorphism", "src/types.ts"))
        .contains("export type Pet = Cat | Dog;")
        .contains("export type Kitten = Animal & { ageMonths?: number };")
        .contains("export type OptionalOwner = Owner | null;");
  }

  @Test
  void nullableItemsOfAnArrayAreParenthesised() {
    var model =
        new ApiModel(
            new Info("Items", "1.0.0", null, null),
            List.of(),
            Map.of(),
            List.of(),
            List.of(),
            Map.of(
                "Names",
                new ArraySchema(
                    new PrimitiveSchema(PrimitiveType.STRING, null, true, null), false, null)));
    assertThat(sdkFile(model, "src/types.ts")).contains("export type Names = (string | null)[];");
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
  void oauthOnlyApiGetsABearerTokenOption() {
    var oauth = new SecurityScheme("oauth", SecurityScheme.Kind.OAUTH2, null, null, null);
    assertThat(clientSource(Map.of("oauth", oauth)))
        .contains("  bearerToken?: string;")
        .contains("this.authHeaders[\"Authorization\"] = `Bearer ${opts.bearerToken}`;");
  }

  @Test
  void openIdConnectOnlyApiGetsABearerTokenOption() {
    var oidc = new SecurityScheme("oidc", SecurityScheme.Kind.OPENID_CONNECT, null, null, null);
    assertThat(clientSource(Map.of("oidc", oidc)))
        .contains("  bearerToken?: string;")
        .contains("this.authHeaders[\"Authorization\"] = `Bearer ${opts.bearerToken}`;");
  }

  @Test
  void constructorErrorNamesWhatToPassWhenNoServersAreGiven() {
    assertThat(clientSource("Petstore", List.of()))
        .contains(
            "throw new Error(\"PetstoreClient: baseUrl is required. The API declares no absolute "
                + "server URL. Pass the full base URL, e.g. https://api.example.com.\");");
  }

  @Test
  void constructorErrorNamesRelativeSlashServerAsNoAbsoluteUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains(
            "throw new Error(\"PetstoreClient: baseUrl is required. The API declares no absolute "
                + "server URL. Pass the full base URL, e.g. https://api.example.com.\");");
  }

  @Test
  void constructorErrorNamesRelativePathServerAndShowsAFullUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains(
            "throw new Error(\"PetstoreClient: baseUrl is required. The first server, /api/v3, is "
                + "not an absolute http(s) URL. Pass the full base URL, e.g. "
                + "https://api.example.com/api/v3.\");");
  }

  @Test
  void constructorErrorNamesTemplatedServer() {
    assertThat(clientSource("Petstore", List.of(new Server("https://{env}.example.com", null))))
        .contains(
            "throw new Error(\"PetstoreClient: baseUrl is required. The first server, "
                + "https://{env}.example.com, has template variables. Pass the full base URL, "
                + "e.g. https://api.example.com.\");");
  }

  @Test
  void readmeDescribesTheDefaultBaseUrlWhenOneExists() {
    assertThat(readmeSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("Requests go to `https://api.example.com/v1` unless `baseUrl` is passed.");
  }

  @Test
  void readmeNamesTheRelativeServerWhenNoDefaultExists() {
    assertThat(readmeSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains("The first server, /api/v3, is not an absolute http(s) URL.");
  }

  @Test
  void quoteEscapesQuotesBackslashesControlCharactersAndSeparators() {
    assertThat(TsClientView.quote("a\"b\\c\nd\t\r\u2028\u0001"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\t\\r\\u2028\\u0001\"");
  }

  @Test
  void docTextCollapsesWhitespaceAndNeutralisesCommentClose() {
    assertThat(TsClientView.docText("  Pets */ \n  Store\tAPI ")).isEqualTo("Pets *\\/ Store API");
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
        .contains("The API declares no absolute server URL");
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

  @Test
  void deprecatedOperationIsMarkedAndItsTextIsEscaped() {
    var model =
        withListPets(op ->
            new Operation(
                op.operationId(),
                op.operationIdSynthesized(),
                op.method(),
                op.path(),
                op.tag(),
                "Lists */ pets",
                "All of them.",
                op.parameters(),
                op.requestBody(),
                op.responses(),
                op.security(),
                true));
    assertThat(sdkFile(model, "src/resources/pets.ts"))
        .contains(
            "  /**\n   * Lists *\\/ pets All of them.\n   * @deprecated\n   */\n  async listPets(");
  }

  @Test
  void queryNamesThatAreNotIdentifiersAreQuoted() {
    var apiVersion =
        new Parameter(
            "api-version",
            ParamLocation.QUERY,
            true,
            new PrimitiveSchema(PrimitiveType.STRING, null, false, null),
            null);
    var model =
        withListPets(op ->
            new Operation(
                op.operationId(),
                op.operationIdSynthesized(),
                op.method(),
                op.path(),
                op.tag(),
                op.summary(),
                op.description(),
                List.of(apiVersion),
                op.requestBody(),
                op.responses(),
                op.security(),
                op.deprecated()));
    assertThat(sdkFile(model, "src/resources/pets.ts"))
        .contains("async listPets(params: { \"api-version\": string }): Promise<Pet[]> {")
        .contains("query: { \"api-version\": params[\"api-version\"] },");
  }

  private String packageJson(PackageNames names) {
    return content(generate("petstore", ".emitkit/", names), "package.json");
  }

  private String clientSource(String title, List<Server> servers) {
    return sdkFile(
        model(title, servers, FixtureSpecs.load("petstore").securitySchemes()), "src/client.ts");
  }

  private String clientSource(Map<String, SecurityScheme> schemes) {
    return sdkFile(
        model("Petstore", FixtureSpecs.load("petstore").servers(), schemes), "src/client.ts");
  }

  private String readmeSource(String title, List<Server> servers) {
    return sdkFile(model(title, servers, Map.of()), "README.md");
  }

  /** The petstore model with its listPets operation replaced by {@code change}. */
  private static ApiModel withListPets(UnaryOperator<Operation> change) {
    ApiModel base = FixtureSpecs.load("petstore");
    List<Operation> operations =
        base.operations().stream()
            .map(op -> op.operationId().equals("listPets") ? change.apply(op) : op)
            .toList();
    return new ApiModel(
        base.info(),
        base.servers(),
        base.securitySchemes(),
        base.tags(),
        operations,
        base.schemas());
  }

  private static ApiModel model(
      String title, List<Server> servers, Map<String, SecurityScheme> schemes) {
    ApiModel base = FixtureSpecs.load("petstore");
    return new ApiModel(
        new Info(title, "1.0.0", null, null),
        servers,
        schemes,
        base.tags(),
        base.operations(),
        base.schemas());
  }

  private String sdkFile(ApiModel model, String relative) {
    return content(generate(model, ".emitkit/", names(null, "petstore-sdk")), relative);
  }

  private String content(String fixture, String relative) {
    return content(generate(fixture, ".emitkit/", names(null, "petstore-sdk")), relative);
  }

  private List<String> paths(String fixture) {
    return generate(fixture, ".emitkit/", names(null, "petstore-sdk")).stream()
        .map(GeneratedFile::path)
        .toList();
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
