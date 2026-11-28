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
import dev.emitkit.spec.parse.SpecParser;
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
        "multi-tag",
        "bookshelf"
      })
  void matchesGolden(String fixture) {
    GoldenFiles.assertMatches(fixture, generator.generate(FixtureSpecs.load(fixture), ctx()));
  }

  @Test
  void outputIsByteStable() {
    var model = FixtureSpecs.load("polymorphism");
    assertThat(generator.generate(model, ctx())).isEqualTo(generator.generate(model, ctx()));
  }

  @Test
  void idIsJAVA_SDK() {
    assertThat(generator.id()).isEqualTo(GeneratorId.JAVA_SDK);
  }

  @Test
  void schemaNamedObjectIsRenamed() {
    assertThat(relativePaths("reserved-words")).contains("model/Object_.java");
  }

  @Test
  void inlineObjectGetsNestedRecord() {
    // deep-nesting has no Order schema; its first inline object is Deep.level1.
    assertThat(content("deep-nesting", SRC + "model/Deep.java"))
        .contains("public record DeepLevel1(");
  }

  @Test
  void discriminatedUnionIsASealedInterfaceAndAnUndiscriminatedUnionIsJsonNode() {
    var files = generate("polymorphism", ".emitkit/", names("com.acme", "pets-sdk"));
    assertThat(content(files, SRC + "model/Pet.java"))
        .contains("public sealed interface Pet permits Cat, Dog {}");
    assertThat(content(files, SRC + "model/Cat.java"))
        .contains("public record Cat(", "implements Pet");
    assertThat(content(files, SRC + "resources/DefaultApi.java"))
        .contains("public JsonNode listShapes()");
  }

  @Test
  void discriminatorSubtypesAreNamedByTheMappingValue() {
    assertThat(content("bookshelf", SRC + "model/BookFormat.java"))
        .contains(
            "@JsonSubTypes.Type(value = PrintFormat.class, name = \"print\")",
            "@JsonSubTypes.Type(value = EbookFormat.class, name = \"ebook\")",
            "@JsonSubTypes.Type(value = AudioFormat.class, name = \"audio\")");
  }

  @Test
  void discriminatorSubtypeNameFallsBackToTheSingleEnumValueThenTheSchemaName() {
    // Circle is named by the mapping, which beats its enum; Square has no mapping, so its
    // single enum value names it; Triangle has neither, so the schema name does.
    var shapes =
        """
        openapi: 3.0.3
        info: {title: Shapes, version: 1.0.0}
        paths: {}
        components:
          schemas:
            Shape:
              oneOf:
                - $ref: '#/components/schemas/Circle'
                - $ref: '#/components/schemas/Square'
                - $ref: '#/components/schemas/Triangle'
              discriminator:
                propertyName: kind
                mapping:
                  round: '#/components/schemas/Circle'
            Circle: {type: object, required: [kind], properties: {kind: {type: string, enum: [circle]}}}
            Square: {type: object, required: [kind], properties: {kind: {type: string, enum: [square]}}}
            Triangle: {type: object, properties: {kind: {type: string}}}
        """;
    assertThat(content(generateFrom(shapes), SRC + "model/Shape.java"))
        .contains(
            "@JsonSubTypes.Type(value = Circle.class, name = \"round\")",
            "@JsonSubTypes.Type(value = Square.class, name = \"square\")",
            "@JsonSubTypes.Type(value = Triangle.class, name = \"Triangle\")");
  }

  @Test
  void headerAndCookieParametersAreSentAndRequiredOnesAreArguments() {
    var files = generate("bookshelf", ".emitkit/", names("com.acme", "pets-sdk"));
    assertThat(content(files, SRC + "resources/BooksApi.java"))
        .contains(
            "public Book createBook(String idempotencyKey, BookInput body) {",
            "headers.put(\"Idempotency-Key\", idempotencyKey);");
    assertThat(content(files, SRC + "resources/LoansApi.java"))
        .contains(
            "public Loan createLoan(LoanRequest body, CreateLoanOptions options) {",
            "if (options != null && options.session() != null) {",
            "cookies.put(\"session\", options.session());",
            "List.of(\"apiKeyAuth\", \"oauth2\")");
  }

  @Test
  void operationJavadocKeepsTheDescriptionAfterTheSummary() {
    assertThat(content("bookshelf", SRC + "resources/BooksApi.java"))
        .contains(
            "   * List books\n"
                + "   *\n"
                + "   * <p>Returns a page of books, optionally filtered by genre and availability.\n"
                + "   */");
  }

  @Test
  void operationJavadocEscapesMarkupCommentClosersAndLeadingAt() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Docs, version: 1.0.0}
        paths:
          /items:
            get:
              operationId: listItems
              summary: 'Find <b>items</b> & more */'
              description: 'Rows > 5 and @deprecated text'
              responses: {'200': {description: ok}}
          /admin:
            get:
              operationId: getAdmin
              summary: '@Admin only'
              responses: {'200': {description: ok}}
        """;
    var files = generateFrom(spec);
    assertThat(content(files, SRC + "resources/DefaultApi.java"))
        .contains(
            "   * Find &lt;b&gt;items&lt;/b&gt; &amp; more *&#47;\n"
                + "   *\n"
                + "   * <p>Rows &gt; 5 and @deprecated text\n"
                + "   */",
            "  /** &#64;Admin only */");
  }

  @Test
  void inlineEnumParameterIsAGeneratedEnumThatSendsItsWireValue() {
    var files = generate("bookshelf", ".emitkit/", names("com.acme", "pets-sdk"));
    assertThat(content(files, SRC + "resources/AuthorsApi.java"))
        .contains(
            "public enum ListAuthorsSort {",
            "ASC(\"asc\"),",
            "DESC(\"desc\");",
            "ListAuthorsSort sort",
            "query.put(\"sort\", String.valueOf(options.sort().value()));")
        .doesNotContain("String sort");
  }

  // Review round: one API key per request, cookie apiKeys, credentials winning, basic with bearer.

  private static final String KEY_SCHEMES =
      """
      openapi: 3.0.3
      info: {title: Keys, version: 1.0.0}
      paths:
        /items:
          get:
            operationId: listItems
            security:
              - {headerKey: [], queryKey: [], cookieKey: []}
            responses: {'200': {description: ok}}
          post:
            operationId: createItem
            security:
              - {queryKey: [], cookieKey: []}
            responses: {'200': {description: ok}}
      components:
        securitySchemes:
          headerKey: {type: apiKey, in: header, name: X-API-Key}
          queryKey: {type: apiKey, in: query, name: api_key}
          cookieKey: {type: apiKey, in: cookie, name: session_id}
      """;

  @Test
  void anOperationSendsOneApiKeyPreferringAHeaderOrCookieScheme() {
    var files = generateFrom(KEY_SCHEMES);
    assertThat(content(files, SRC + "resources/DefaultApi.java"))
        .contains("List.of(\"headerKey\")")
        .contains("List.of(\"cookieKey\")")
        .doesNotContain("\"queryKey\"");
  }

  @Test
  void aCookieApiKeyIsSentAsACookie() {
    assertThat(
            generateFrom(KEY_SCHEMES).stream()
                .filter(f -> f.path().endsWith("Client.java"))
                .findFirst()
                .orElseThrow()
                .content())
        .contains(
            "new ClientConfig.Credential(Map.of(), Map.of(), Map.of(\"session_id\", apiKey))");
  }

  @Test
  void requestHeadersAreCaseInsensitiveAndCredentialsWinOverParameters() {
    assertThat(clientSource(FixtureSpecs.load("auth-schemes")))
        .contains("new TreeMap<>(String.CASE_INSENSITIVE_ORDER)")
        .contains("allQuery.putAll(credQuery);")
        .contains("allHeaders.putAll(credHeaders);");
  }

  @Test
  void aBasicCredentialIsRegisteredEvenWhenABearerTokenIsSet() {
    assertThat(clientSource(FixtureSpecs.load("auth-schemes")))
        .contains("if (basicUsername != null) {")
        .doesNotContain("bearerToken == null")
        .contains("credHeaders::putIfAbsent")
        .doesNotContain("basicUsername != null && ");
  }

  @Test
  void aQueryAndAHeaderParameterWithTheSameNameGetDistinctJavaNames() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Clash, version: 1.0.0}
        paths:
          /items:
            get:
              operationId: listItems
              parameters:
                - {name: limit, in: query, schema: {type: integer}}
                - {name: limit, in: header, schema: {type: integer}}
                - {name: session, in: cookie, schema: {type: string}}
                - {name: session, in: query, schema: {type: string}}
              responses: {'200': {description: ok}}
        """;
    assertThat(content(generateFrom(spec), SRC + "resources/DefaultApi.java"))
        .contains(
            "Integer headerLimit",
            "headers.put(\"limit\", String.valueOf(options.headerLimit()));",
            "query.put(\"limit\", String.valueOf(options.limit()));",
            "cookies.put(\"session\", options.session());",
            "query.put(\"session\", options.querySession());");
  }

  @Test
  void reservedHeaderParametersAreNeitherArgumentsNorOptions() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Reserved, version: 1.0.0}
        paths:
          /items:
            get:
              operationId: listItems
              parameters:
                - {name: Authorization, in: header, schema: {type: string}}
                - {name: accept, in: header, schema: {type: string}}
                - {name: Content-Type, in: header, schema: {type: string}}
              responses: {'200': {description: ok}}
        """;
    assertThat(content(generateFrom(spec), SRC + "resources/DefaultApi.java"))
        .doesNotContain("ListItemsOptions", "headers.put", "Authorization", "accept");
  }

  @Test
  void anInlineEnumNamedLikeTheOptionsRecordIsSuffixedWithParam() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Clash, version: 1.0.0}
        paths:
          /items:
            get:
              operationId: listItems
              parameters:
                - {name: options, in: query, schema: {type: string, enum: [a, b]}}
              responses: {'200': {description: ok}}
        """;
    assertThat(content(generateFrom(spec), SRC + "resources/DefaultApi.java"))
        .contains("public enum ListItemsOptionsParam {", "ListItemsOptionsParam options_");
  }

  @Test
  void anInlineEnumNamedLikeAModelIsSuffixedWithParam() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Clash, version: 1.0.0}
        paths:
          /items:
            get:
              operationId: listItems
              parameters:
                - {name: sort, in: query, schema: {type: string, enum: [asc, desc]}}
              responses: {'200': {description: ok}}
        components:
          schemas:
            ListItemsSort: {type: object, properties: {name: {type: string}}}
        """;
    var files = generateFrom(spec);
    assertThat(content(files, SRC + "resources/DefaultApi.java"))
        .contains("public enum ListItemsSortParam {", "ListItemsSortParam sort");
    assertThat(content(files, SRC + "model/ListItemsSort.java"))
        .contains("public record ListItemsSort(");
  }

  @Test
  void severalMappingKeysForOneOptionAreAllNamed() {
    var spec =
        """
        openapi: 3.0.3
        info: {title: Shapes, version: 1.0.0}
        paths: {}
        components:
          schemas:
            Shape:
              oneOf:
                - $ref: '#/components/schemas/Circle'
                - $ref: '#/components/schemas/Square'
              discriminator:
                propertyName: kind
                mapping:
                  round: '#/components/schemas/Circle'
                  circ: '#/components/schemas/Circle'
            Circle: {type: object, required: [kind], properties: {kind: {type: string}}}
            Square: {type: object, required: [kind], properties: {kind: {type: string, enum: [square]}}}
        """;
    assertThat(content(generateFrom(spec), SRC + "model/Shape.java"))
        .contains("@JsonSubTypes.Type(value = Circle.class, names = {\"round\", \"circ\"})")
        .contains("@JsonSubTypes.Type(value = Square.class, name = \"square\")");
  }

  @Test
  void javadocEscapesInlineTagStarts() {
    assertThat(JavaClientView.docText("see {@code x} and {@link Y}"))
        .isEqualTo("see &#123;@code x} and &#123;@link Y}");
  }

  @Test
  void integerEnumConstantsAreLegalIdentifiers() {
    assertThat(content("enums", SRC + "model/Priority.java")).contains("_1(1),", "@JsonValue");
  }

  @Test
  void eachTagGetsOneResourceAndAClientAccessor() {
    var files = generate("multi-tag", ".emitkit/", names("com.acme", "pets-sdk"));
    assertThat(content(files, SRC + "MultiTagClient.java"))
        .contains(
            "public PetsApi pets() {", "public StoreApi store() {", "public AdminApi admin() {");
    assertThat(relativePaths("multi-tag"))
        .contains("resources/PetsApi.java", "resources/StoreApi.java", "resources/AdminApi.java");
  }

  @Test
  void tagsThatResolveToOneNameShareOneResource() {
    // The tags "user" and "User" both resolve to UserApi, so their operations share it.
    assertThat(relativePaths("reserved-words"))
        .filteredOn(path -> path.equals("resources/UserApi.java"))
        .hasSize(1);
    assertThat(content("reserved-words", SRC + "resources/UserApi.java"))
        .contains("public void class_()", "public void import_()", "public Object_ getItemIn(");
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
  void apiKeySchemesSendTheKeyInTheirOwnLocationAndName() {
    // auth-schemes declares both a header and a query apiKey scheme.
    assertThat(clientSource(FixtureSpecs.load("auth-schemes")))
        .contains("new ClientConfig.Credential(Map.of(\"X-API-Key\", apiKey), Map.of(), Map.of())")
        .contains("new ClientConfig.Credential(Map.of(), Map.of(\"api_key\", apiKey), Map.of())");
  }

  @Test
  void apiKeyInTheQueryIsSentAsAQueryParameter() {
    var queryKey =
        new SecurityScheme("queryKey", SecurityScheme.Kind.API_KEY, "query", "api_key", null);
    assertThat(clientSource(Map.of("queryKey", queryKey)))
        .contains("new ClientConfig.Credential(Map.of(), Map.of(\"api_key\", apiKey), Map.of())")
        .doesNotContain("X-API-Key");
  }

  @Test
  void operationsSendOnlyTheCredentialsOfTheirSecurityRequirement() {
    var files = generate("bookshelf", ".emitkit/", names("com.acme", "pets-sdk"));
    // listAuthors declares "security: []", so it sends no credentials at all.
    assertThat(content(files, SRC + "resources/AuthorsApi.java"))
        .contains("\"/authors\", query, null, null, null, List.of());");
    assertThat(content(files, SRC + "resources/LoansApi.java"))
        .contains("List.of(\"apiKeyAuth\", \"oauth2\")");
    assertThat(content(files, SRC + "resources/BooksApi.java")).contains("List.of(\"bearerAuth\")");
    assertThat(content(files, SRC + "BookshelfApiClient.java"))
        .contains(
            "config.credentials().get(scheme)",
            "credential.headers().forEach(credHeaders::putIfAbsent)");
  }

  @ParameterizedTest
  @EnumSource(
      value = SecurityScheme.Kind.class,
      names = {"OAUTH2", "OPENID_CONNECT"})
  void tokenOnlyApiGetsABearerTokenOption(SecurityScheme.Kind kind) {
    var scheme = new SecurityScheme("auth", kind, null, null, null);
    assertThat(clientSource(Map.of("auth", scheme)))
        .contains("public Builder bearerToken(String bearerToken)")
        .contains("Map.of(\"Authorization\", \"Bearer \" + bearerToken)");
  }

  @Test
  void retriesOnlyIdempotentRequests() {
    assertThat(clientSource(FixtureSpecs.load("petstore")))
        .contains("Set.of(\"GET\", \"HEAD\", \"PUT\", \"DELETE\", \"OPTIONS\")")
        .contains("request.headers().firstValue(\"Idempotency-Key\").isPresent()")
        .contains("if (!retryable || !isRetryable(status)");
  }

  @Test
  void retryAfterSetsTheWaitAndIsCappedAtThirtySeconds() {
    assertThat(clientSource(FixtureSpecs.load("petstore")))
        .contains("response.headers().firstValue(\"Retry-After\")")
        .contains("DateTimeFormatter.RFC_1123_DATE_TIME")
        .contains("Math.min(Math.max(millis, 0), MAX_DELAY_MILLIS)");
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
        .contains("credentials.keySet()")
        .doesNotContain("+ credentials +");
  }

  @Test
  void arrayQueryParamsAreSentCommaSeparatedAndSaySo() {
    assertThat(content(generateFrom(ARRAYS), SRC + "resources/DefaultApi.java"))
        .contains(
            ".stream().map(item -> String.valueOf(item)).collect(Collectors.joining(\",\"))",
            ".stream().map(item -> item).collect(Collectors.joining(\",\"))",
            "Array query parameters are sent comma-separated.")
        .doesNotContain("String.valueOf(ids)");
  }

  @Test
  void parametersAndOperationsNamedLikeGeneratedCodeGetAnUnderscore() {
    assertThat(content(generateFrom(SHADOWING), SRC + "resources/DefaultApi.java"))
        .contains(
            "public String segment_(String client_, String query_, String options_, "
                + "String response_, String body, SegmentOptions options) {",
            "\"/segment/\" + segment(client_)",
            "String body_");
  }

  @Test
  void objectMethodBuilderAndMapperNamesGetAnUnderscore() {
    var files = generateFrom(NAMES);
    assertThat(content(files, SRC + "resources/BuilderApi.java"))
        .contains("public Hook toString_(", "Boolean wait_");
    assertThat(content(files, SRC + "resources/MapperApi.java"))
        .contains("public void getClass_()");
    assertThat(content(files, SRC + "NamesClient.java"))
        .contains("public BuilderApi builder_()", "public MapperApi mapper_()");
    assertThat(content(files, SRC + "model/Hook.java")).contains("String hashCode_");
  }

  @Test
  void operationIdsThatNormalizeToOneMethodAreRejected() {
    assertThatThrownBy(() -> generateFrom(DUPLICATE_OPERATIONS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll("get_user", "getUser");
  }

  @Test
  void schemasThatNormalizeToOneTypeAreRejected() {
    assertThatThrownBy(() -> generateFrom(COLLIDING_SCHEMAS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll("user_item", "UserItem");
  }

  @Test
  void propertiesThatNormalizeToOneComponentAreRejected() {
    assertThatThrownBy(() -> generateFrom(COLLIDING_PROPERTIES))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContainingAll("user_id", "userId");
  }

  private static final String ARRAYS =
      """
      openapi: 3.0.3
      info: {title: Arrays, version: 1.0.0}
      paths:
        /items:
          get:
            operationId: listItems
            parameters:
              - {name: ids, in: query, required: true, schema: {type: array, items: {type: integer}}}
              - {name: tags, in: query, schema: {type: array, items: {type: string}}}
            responses: {'200': {description: ok}}
      """;

  private static final String SHADOWING =
      """
      openapi: 3.0.3
      info: {title: Shadowing, version: 1.0.0}
      paths:
        /segment/{client}:
          post:
            operationId: segment
            parameters:
              - {name: client, in: path, required: true, schema: {type: string}}
              - {name: query, in: query, required: true, schema: {type: string}}
              - {name: options, in: query, required: true, schema: {type: string}}
              - {name: response, in: query, required: true, schema: {type: string}}
              - {name: body, in: query, schema: {type: string}}
            requestBody: {required: true, content: {application/json: {schema: {type: string}}}}
            responses: {'200': {description: ok, content: {application/json: {schema: {type: string}}}}}
      """;

  private static final String COLLIDING_SCHEMAS =
      """
      openapi: 3.0.3
      info: {title: Collide, version: 1.0.0}
      paths: {}
      components:
        schemas:
          user_item: {properties: {id: {type: string}}}
          UserItem: {properties: {id: {type: string}}}
      """;

  private static final String COLLIDING_PROPERTIES =
      """
      openapi: 3.0.3
      info: {title: Collide, version: 1.0.0}
      paths: {}
      components: {schemas: {Account: {properties: {user_id: {type: string}, userId: {type: string}}}}}
      """;

  private static final String NAMES =
      """
      openapi: 3.0.3
      info: {title: Names, version: 1.0.0}
      paths:
        /hooks:
          post:
            operationId: toString
            tags: [builder]
            parameters: [{name: wait, in: query, schema: {type: boolean}}]
            responses: {'200': {description: ok, content: {application/json: {schema: {$ref: '#/components/schemas/Hook'}}}}}
        /maps:
          get:
            operationId: getClass
            tags: [mapper]
            responses: {'200': {description: ok}}
      components:
        schemas:
          Hook: {properties: {hashCode: {type: string}}}
      """;

  private static final String DUPLICATE_OPERATIONS =
      """
      openapi: 3.0.3
      info: {title: Dupes, version: 1.0.0}
      paths:
        /users/{id}:
          get: {operationId: get_user, parameters: [{name: id, in: path, required: true, schema: {type: string}}], responses: {'200': {description: ok}}}
        /other:
          get: {operationId: getUser, responses: {'200': {description: ok}}}
      """;

  private List<GeneratedFile> generateFrom(String yaml) {
    var result = new SpecParser().parse(yaml);
    assertThat(result.errors()).isEmpty();
    return generate(result.model(), ".emitkit/", names("com.acme", "pets-sdk"));
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

  private String content(String fixture, String path) {
    return content(generate(fixture, ".emitkit/", names("com.acme", "pets-sdk")), path);
  }

  /** Paths under the package root, such as {@code model/Pet.java}. */
  private List<String> relativePaths(String fixture) {
    return generate(fixture, ".emitkit/", names("com.acme", "pets-sdk")).stream()
        .map(GeneratedFile::path)
        .filter(path -> path.startsWith(SRC))
        .map(path -> path.substring(SRC.length()))
        .toList();
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
