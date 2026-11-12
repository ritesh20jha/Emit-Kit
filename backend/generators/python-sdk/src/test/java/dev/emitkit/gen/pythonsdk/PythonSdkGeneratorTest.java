package dev.emitkit.gen.pythonsdk;

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

class PythonSdkGeneratorTest {

  private static final String ROOT = ".emitkit/sdk/python/";

  private final PythonSdkGenerator generator = new PythonSdkGenerator();

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
  void idIsPY_SDK() {
    assertThat(generator.id()).isEqualTo(GeneratorId.PY_SDK);
  }

  @Test
  void packageNameFromPypiName() {
    assertThat(generate("petstore", ".emitkit/", names("acme-pets")))
        .extracting(GeneratedFile::path)
        .noneMatch(path -> path.contains("acme-pets"))
        .contains(
            ROOT + "acme_pets/client.py",
            ROOT + "acme_pets/errors.py",
            ROOT + "acme_pets/__init__.py",
            ROOT + "pyproject.toml",
            ROOT + "README.md");
  }

  @Test
  void customStubIsMarked() {
    assertThat(generate("petstore", ".emitkit/", names("acme-pets")))
        .filteredOn(f -> f.path().equals(ROOT + "acme_pets/custom/__init__.py"))
        .singleElement()
        .extracting(GeneratedFile::kind)
        .isEqualTo(FileKind.CUSTOM_STUB);
  }

  @ParameterizedTest
  @ValueSource(strings = {".emitkit/", ".emitkit", ".emitkit//"})
  void outputDirIsNormalisedToOneTrailingSlash(String outputDir) {
    assertThat(generate("petstore", outputDir, names("acme-pets")))
        .extracting(GeneratedFile::path)
        .allSatisfy(path -> assertThat(path).startsWith(ROOT))
        .contains(ROOT + "acme_pets/client.py");
  }

  @Test
  void emptyOutputDirWritesUnderSdkRoot() {
    assertThat(generate("petstore", "", names("acme-pets")))
        .extracting(GeneratedFile::path)
        .contains("sdk/python/acme_pets/client.py");
  }

  @Test
  void pyprojectDeclaresNameVersionAndDependencies() {
    assertThat(pyproject(names("acme-pets")))
        .contains("name = \"acme-pets\"")
        .contains("version = \"1.2.3\"")
        .contains("requires-python = \">=3.9\"")
        .contains("\"httpx>=0.27\"")
        .contains("\"pydantic>=2.6\"")
        .contains("build-backend = \"hatchling.build\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Petstore", "Petstore Client"})
  void clientNameEndsWithClientSuffixOnce(String title) {
    assertThat(clientSource(title, List.of())).contains("class PetstoreClient:");
  }

  @Test
  void asyncClientUsesHttpxAsyncClient() {
    assertThat(clientSource("Petstore", List.of()))
        .contains("class AsyncPetstoreClient:")
        .contains("self._http = http_client if http_client is not None else httpx.AsyncClient()")
        .contains("async def aclose(self) -> None:");
  }

  @Test
  void apiKeyIsSentInHeaderOnlyWhenAHeaderSchemeExists() {
    // auth-schemes declares both a header and a query apiKey scheme.
    String client =
        content(generate("auth-schemes", ".emitkit/", names("acme-pets")), "acme_pets/client.py");
    assertThat(client)
        .contains("self._headers[\"X-API-Key\"] = api_key")
        .doesNotContain("self._params[\"api_key\"] = api_key");
  }

  @Test
  void apiKeyFallsBackToTheQueryParameterWithoutAHeaderScheme() {
    var queryKey =
        new SecurityScheme("queryKey", SecurityScheme.Kind.API_KEY, "query", "api_key", null);
    assertThat(clientSource(Map.of("queryKey", queryKey)))
        .contains("self._params[\"api_key\"] = api_key")
        .doesNotContain("self._headers[\"");
  }

  @Test
  void oauthOnlyApiGetsABearerTokenOption() {
    var oauth = new SecurityScheme("oauth", SecurityScheme.Kind.OAUTH2, null, null, null);
    assertThat(clientSource(Map.of("oauth", oauth)))
        .contains("bearer_token: Optional[str] = None,")
        .contains("self._headers[\"Authorization\"] = f\"Bearer {bearer_token}\"");
  }

  @Test
  void openIdConnectOnlyApiGetsABearerTokenOption() {
    var oidc = new SecurityScheme("oidc", SecurityScheme.Kind.OPENID_CONNECT, null, null, null);
    assertThat(clientSource(Map.of("oidc", oidc)))
        .contains("bearer_token: Optional[str] = None,")
        .contains("self._headers[\"Authorization\"] = f\"Bearer {bearer_token}\"");
  }

  @Test
  void basicOptionIsAPlainTupleAndBearerWins() {
    assertThat(clientSource("auth-schemes"))
        .contains("basic: Optional[Tuple[str, str]] = None,")
        .contains("if basic is not None and \"Authorization\" not in self._headers:");
  }

  @Test
  void constructorErrorNamesWhatToPassWhenNoServersAreGiven() {
    assertThat(clientSource("Petstore", List.of()))
        .contains(
            "\"PetstoreClient: base_url is required. The API declares no absolute "
                + "server URL. Pass the full base URL, e.g. https://api.example.com.\"");
  }

  @Test
  void constructorErrorNamesRelativePathServerAndShowsAFullUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains(
            "\"PetstoreClient: base_url is required. The first server, /api/v3, "
                + "is not an absolute http(s) URL. Pass the full base URL, e.g. "
                + "https://api.example.com/api/v3.\"");
  }

  @Test
  void constructorErrorNamesTemplatedServer() {
    assertThat(clientSource("Petstore", List.of(new Server("https://{env}.example.com", null))))
        .contains(
            "\"PetstoreClient: base_url is required. The first server, "
                + "https://{env}.example.com, has template variables. Pass the full base URL, "
                + "e.g. https://api.example.com.\"");
  }

  @Test
  void firstAbsoluteServerIsTheDefaultBaseUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("DEFAULT_BASE_URL = \"https://api.example.com/v1\"");
  }

  @Test
  void relativeServerIsNotADefaultBaseUrl() {
    // The parser reports a relative "/" server when the document declares none.
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains("DEFAULT_BASE_URL = \"\"");
  }

  @Test
  void readmeDescribesTheDefaultBaseUrlWhenOneExists() {
    assertThat(readmeSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("Requests go to `https://api.example.com/v1` unless `base_url` is passed.");
  }

  @Test
  void readmeNamesTheRelativeServerWhenNoDefaultExists() {
    assertThat(readmeSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains("The first server, /api/v3, is not an absolute http(s) URL.");
  }

  @Test
  void quoteEscapesQuotesBackslashesControlCharactersAndSeparators() {
    assertThat(PyClientView.quote("a\"b\\c\nd\t\r \u0001"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\t\\r\\u2028\\u0001\"");
  }

  @Test
  void docTextCannotCloseADocstring() {
    assertThat(PyClientView.docText("  Pets \"\"\" \\ \n  Store\tAPI "))
        .isEqualTo("Pets \\\"\\\"\\\" \\\\ Store API");
  }

  @Test
  void titleCannotBreakTheClientDocstring() {
    assertThat(clientSource("Pets \"\"\" Store", List.of()))
        .contains("\"\"\"Client for the Pets \\\"\\\"\\\" Store API.\"\"\"")
        .contains("class PetsStoreClient:");
  }

  private String pyproject(PackageNames names) {
    return content(generate("petstore", ".emitkit/", names), "pyproject.toml");
  }

  private String clientSource(String title, List<Server> servers) {
    return sdkFile(
        model(title, servers, FixtureSpecs.load("petstore").securitySchemes()),
        "acme_pets/client.py");
  }

  private String clientSource(Map<String, SecurityScheme> schemes) {
    return sdkFile(
        model("Petstore", FixtureSpecs.load("petstore").servers(), schemes), "acme_pets/client.py");
  }

  private String clientSource(String fixture) {
    return sdkFile(FixtureSpecs.load(fixture), "acme_pets/client.py");
  }

  private String readmeSource(String title, List<Server> servers) {
    return sdkFile(model(title, servers, Map.of()), "README.md");
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
    return content(generate(model, ".emitkit/", names("acme-pets")), relative);
  }

  private static String content(List<GeneratedFile> files, String relative) {
    return files.stream()
        .filter(f -> f.path().equals(ROOT + relative))
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
        new GeneratorContext(outputDir, "1.2.3", names, List.of("python"), Optional.empty()));
  }

  private GeneratorContext ctx() {
    return new GeneratorContext(
        ".emitkit/", "1.2.3", names("acme-pets"), List.of("python"), Optional.empty());
  }

  private static PackageNames names(String pypiName) {
    return new PackageNames(null, "petstore-sdk", pypiName, "dev.acme", "petstore");
  }
}
