package dev.emitkit.gen.javasdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaSdkGeneratorTest {

  private static final String ROOT = ".emitkit/sdk/java/";
  private static final String SRC = ROOT + "src/main/java/com/acme/petssdk/";
  private static final String CLIENT = SRC + "PetstoreClient.java";

  private final JavaSdkGenerator generator = new JavaSdkGenerator();

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
  void idIsJAVA_SDK() {
    assertThat(generator.id()).isEqualTo(GeneratorId.JAVA_SDK);
  }

  @Test
  void customStubIsMarked() {
    assertThat(generate("petstore", ".emitkit/", names("com.acme", "pets-sdk")))
        .filteredOn(f -> f.path().equals(SRC + "custom/package-info.java"))
        .singleElement()
        .extracting(GeneratedFile::kind)
        .isEqualTo(FileKind.CUSTOM_STUB);
  }

  @Test
  void basePackageFromMavenCoords() {
    var files = generate("petstore", ".emitkit/", names("com.acme", "pets-sdk"));
    assertThat(content(files, CLIENT)).contains("package com.acme.petssdk;");
    assertThat(content(files, ROOT + "build.gradle.kts"))
        .contains(
            "group = \"com.acme\"",
            "version = \"1.2.3\"",
            "artifactId = \"pets-sdk\"",
            "options.release.set(17)");
    assertThat(content(files, ROOT + "settings.gradle.kts"))
        .contains("rootProject.name = \"pets-sdk\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {".emitkit/", ".emitkit", ".emitkit//"})
  void outputDirIsNormalisedToOneTrailingSlash(String outputDir) {
    assertThat(generate("petstore", outputDir, names("com.acme", "pets-sdk")))
        .extracting(GeneratedFile::path)
        .allSatisfy(path -> assertThat(path).startsWith(ROOT))
        .contains(CLIENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Petstore", "Petstore Client"})
  void clientNameEndsWithClientSuffixOnce(String title) {
    assertThat(clientSource(title, List.of())).contains("public final class PetstoreClient {");
  }

  @Test
  void apiKeyIsSentInHeaderOnlyWhenAHeaderSchemeExists() {
    // auth-schemes declares both a header and a query apiKey scheme.
    assertThat(clientSource(FixtureSpecs.load("auth-schemes")))
        .contains("headers.put(\"X-API-Key\", apiKey);")
        .doesNotContain("\"api_key\"");
  }

  @Test
  void apiKeyFallsBackToTheQueryParameterWithoutAHeaderScheme() {
    var queryKey =
        new SecurityScheme("queryKey", SecurityScheme.Kind.API_KEY, "query", "api_key", null);
    assertThat(clientSource(Map.of("queryKey", queryKey)))
        .contains("query.put(\"api_key\", apiKey);")
        .doesNotContain("headers.put(\"");
  }

  @ParameterizedTest
  @EnumSource(
      value = SecurityScheme.Kind.class,
      names = {"OAUTH2", "OPENID_CONNECT"})
  void tokenOnlyApiGetsABearerTokenOption(SecurityScheme.Kind kind) {
    var scheme = new SecurityScheme("auth", kind, null, null, null);
    assertThat(clientSource(Map.of("auth", scheme)))
        .contains("public Builder bearerToken(String bearerToken)")
        .contains("headers.put(\"Authorization\", \"Bearer \" + bearerToken);");
  }

  @Test
  void noAbsoluteServerAsksForAFullBaseUrl() {
    String advice =
        "The API declares no absolute server URL. Pass the full base URL, e.g. "
            + "https://api.example.com.";
    // The parser reports a relative "/" server when the document declares none.
    assertThat(clientSource("Petstore", List.of())).contains(missingBaseUrl(advice));
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains(missingBaseUrl(advice));
  }

  @Test
  void relativePathServerIsNamedAndShownAsAFullUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains(
            missingBaseUrl(
                "The first server, /api/v3, is not an absolute http(s) URL. Pass the full base "
                    + "URL, e.g. https://api.example.com/api/v3."));
  }

  @Test
  void templatedServerIsNamed() {
    assertThat(clientSource("Petstore", List.of(new Server("https://{env}.example.com", null))))
        .contains(
            missingBaseUrl(
                "The first server, https://{env}.example.com, has template variables. Pass the "
                    + "full base URL, e.g. https://api.example.com."));
  }

  @Test
  void firstAbsoluteServerIsTheDefaultBaseUrl() {
    assertThat(clientSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("DEFAULT_BASE_URL = \"https://api.example.com/v1\";");
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains("DEFAULT_BASE_URL = \"\";");
  }

  @Test
  void readmeDescribesTheBaseUrl() {
    assertThat(readme(List.of(new Server("https://api.example.com/v1", null))))
        .contains("Requests go to `https://api.example.com/v1` unless `baseUrl` is passed.");
    assertThat(readme(List.of(new Server("/api/v3", null))))
        .contains("The first server, /api/v3, is not an absolute http(s) URL.");
  }

  @Test
  void quoteEscapesAndNeverEmitsAUnicodeEscapeJavacWouldTranslate() {
    assertThat(JavaClientView.quote("a\"b\\c\nd\t\r\u2028\u0001\u00e9"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\t\\r\\u2028\\001\\u00E9\"");
    // javac rewrites \\uXXXX before lexing; a doubled backslash must stay literal.
    assertThat(JavaClientView.quote("\\u000a")).isEqualTo("\"\\\\u000a\"");
  }

  @Test
  void docTextCollapsesWhitespaceAndNeutralisesCommentCloseAndUnicodeEscapes() {
    assertThat(JavaClientView.docText("  Pets */ \n  Store\tAPI "))
        .isEqualTo("Pets *&#47; Store API");
    // \\u002a is '*' after javac's unicode translation, so the backslash must go.
    assertThat(JavaClientView.docText("\\u002a/")).isEqualTo("&#92;u002a/");
  }

  @Test
  void titleCannotCloseTheDocComment() {
    assertThat(clientSource("Pets */ Store", List.of()))
        .contains("/** Client for the Pets *&#47; Store API. */")
        .contains("public final class PetsStoreClient {");
  }

  @Test
  void clientNamedAfterAnImportedTypeDoesNotClash() {
    // A name ending in Client is not suffixed, so it must not also import java.net.http.HttpClient.
    assertThat(clientSource("Http Client", List.of()))
        .contains("public final class HttpClient {")
        .doesNotContain("import java.net.http.HttpClient;")
        .contains("private java.net.http.HttpClient httpClient;");
  }

  @Test
  void coordinatesThatCannotSitInAKotlinStringAreRejected() {
    assertThatThrownBy(() -> generate("petstore", ".emitkit/", names("com.acme", "pets sdk")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("artifactId");
  }

  @Test
  void requestsHaveATimeoutThatCallersCanSet() {
    assertThat(clientSource("Petstore", List.of()))
        .contains("public Builder timeout(Duration timeout)")
        .contains("builder.timeout(config.timeout());");
  }

  @Test
  void configToStringNamesCredentialsButNeverPrintsThem() {
    assertThat(
            content(
                generate("auth-schemes", ".emitkit/", names("com.acme", "pets-sdk")),
                SRC + "ClientConfig.java"))
        .contains("public String toString()")
        .contains("authHeaders.keySet()")
        .contains("authQuery.keySet()")
        .doesNotContain("+ authHeaders +")
        .doesNotContain("+ authQuery +");
  }

  private static String missingBaseUrl(String advice) {
    return "throw new IllegalStateException(\"PetstoreClient: baseUrl is required. "
        + advice
        + "\");";
  }

  private String clientSource(String title, List<Server> servers) {
    return clientSource(model(title, servers, FixtureSpecs.load("petstore").securitySchemes()));
  }

  private String clientSource(Map<String, SecurityScheme> schemes) {
    return clientSource(model("Petstore", FixtureSpecs.load("petstore").servers(), schemes));
  }

  private String clientSource(ApiModel model) {
    return generate(model, ".emitkit/", names("com.acme", "pets-sdk")).stream()
        .filter(f -> f.path().startsWith(SRC) && f.path().endsWith("Client.java"))
        .findFirst()
        .orElseThrow()
        .content();
  }

  private String readme(List<Server> servers) {
    return content(
        generate(model("Petstore", servers, Map.of()), ".emitkit/", names("com.acme", "pets-sdk")),
        ROOT + "README.md");
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

  private static String content(List<GeneratedFile> files, String path) {
    return files.stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow().content();
  }

  private List<GeneratedFile> generate(String fixture, String outputDir, PackageNames names) {
    return generate(FixtureSpecs.load(fixture), outputDir, names);
  }

  private List<GeneratedFile> generate(ApiModel model, String outputDir, PackageNames names) {
    return generator.generate(
        model, new GeneratorContext(outputDir, "1.2.3", names, List.of("java"), Optional.empty()));
  }

  private GeneratorContext ctx() {
    return new GeneratorContext(
        ".emitkit/", "1.2.3", names("com.acme", "pets-sdk"), List.of("java"), Optional.empty());
  }

  private static PackageNames names(String group, String artifact) {
    return new PackageNames(null, "petstore-sdk", "petstore", group, artifact);
  }
}
