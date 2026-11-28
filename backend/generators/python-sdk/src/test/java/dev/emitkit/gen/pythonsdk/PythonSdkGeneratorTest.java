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
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.Info;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PythonSdkGeneratorTest {

  private static final String ROOT = ".emitkit/sdk/python/";

  private final PythonSdkGenerator generator = new PythonSdkGenerator();

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
    var model = FixtureSpecs.load("petstore");
    assertThat(generator.generate(model, ctx())).isEqualTo(generator.generate(model, ctx()));
  }

  @Test
  void reservedPropertyGetsAlias() {
    assertThat(content("reserved-words", "models.py"))
        .contains("from_: ")
        .contains("validation_alias=\"from\", serialization_alias=\"from\"");
  }

  @Test
  void oneOfIsAPlainUnionWithoutDiscriminator() {
    assertThat(content("polymorphism", "models.py"))
        .contains("Pet = Union[Cat, Dog]")
        .doesNotContain("discriminator");
  }

  @Test
  void discriminatedOneOfIsAnnotatedAndItsDiscriminatorFieldsAreLiterals() {
    assertThat(content("bookshelf", "models.py"))
        .contains(
            "BookFormat = Annotated[Union[PrintFormat, EbookFormat, AudioFormat], "
                + "Field(discriminator=\"kind\")]")
        .contains("    kind: Literal[\"print\"]")
        .contains("    kind: Literal[\"ebook\"]")
        .contains("    kind: Literal[\"audio\"]")
        .doesNotContain("class PrintFormatKind");
  }

  @Test
  void oneOfWhoseOptionsDoNotAllFixTheDiscriminatorStaysPlain() {
    // Probe1 allows two kinds, so the discriminator cannot tell the options apart.
    var twoKinds = new EnumSchema(PrimitiveType.STRING, List.of("a", "b"), false, null);
    var oneKind = new EnumSchema(PrimitiveType.STRING, List.of("c"), false, null);
    var probe1 = new ObjectSchema(Map.of("kind", twoKinds), Set.of("kind"), null, false, null);
    var probe2 = new ObjectSchema(Map.of("kind", oneKind), Set.of("kind"), null, false, null);
    var probes =
        new OneOfSchema(
            List.of(new RefSchema("Probe1", false, null), new RefSchema("Probe2", false, null)),
            "kind",
            false,
            null);
    assertThat(
            sdkFile(
                extended(
                    FixtureSpecs.load("bookshelf"),
                    null,
                    Map.of("Probe1", probe1, "Probe2", probe2, "Probes", probes)),
                "acme_pets/models.py"))
        .contains("Probes = Union[Probe1, Probe2]")
        .doesNotContain("Probes = Annotated");
  }

  @Test
  void enumQueryParameterIsSentAsItsValue() {
    assertThat(content("enums", "resources/default.py"))
        .contains(
            // The dump is of the bare enum type, which pydantic 2.8's stubs type; the None case
            // is handled here rather than by an Optional adapter that mypy cannot type.
            "query=_drop_none({\"color\": None if color is None else "
                + "TypeAdapter(Color).dump_python(color, mode='json')})");
  }

  @Test
  void enumPathParameterIsSentAsItsValue() {
    var colorItem =
        new Operation(
            "getColorItem",
            false,
            "get",
            "/colors/{color}",
            "items",
            null,
            null,
            List.of(
                new Parameter(
                    "color", ParamLocation.PATH, true, new RefSchema("Color", false, null), null)),
            null,
            Map.of("200", new Response("200", "ok", null)),
            List.of(),
            false);
    assertThat(
            sdkFile(
                extended(FixtureSpecs.load("enums"), colorItem, Map.of()),
                "acme_pets/resources/items.py"))
        .contains(
            "f\"/colors/{_quote(str(TypeAdapter(Color).dump_python(color, mode='json')), "
                + "safe='')}\"");
  }

  @Test
  void baseModelAttributeNamesGetAnAliasedSuffix() {
    var str = new PrimitiveSchema(PrimitiveType.STRING, null, false, null);
    var probe =
        new ObjectSchema(
            Map.of("schema", str, "json", str, "model_name", str), Set.of(), null, false, null);
    assertThat(
            sdkFile(
                extended(FixtureSpecs.load("reserved-words"), null, Map.of("Probe", probe)),
                "acme_pets/models.py"))
        .contains(
            "schema_: Optional[str] = Field(default=None, validation_alias=\"schema\", serialization_alias=\"schema\")")
        .contains(
            "json_: Optional[str] = Field(default=None, validation_alias=\"json\", serialization_alias=\"json\")")
        .contains(
            "field_model_name: Optional[str] = Field(default=None, validation_alias=\"model_name\", serialization_alias=\"model_name\")");
  }

  /** The base document with one more operation (when given) and more component schemas. */
  private static ApiModel extended(
      ApiModel base, Operation operation, Map<String, Schema> schemas) {
    var operations = new ArrayList<>(base.operations());
    if (operation != null) {
      operations.add(operation);
    }
    var merged = new LinkedHashMap<>(base.schemas());
    merged.putAll(schemas);
    return new ApiModel(
        base.info(), base.servers(), base.securitySchemes(), base.tags(), operations, merged);
  }

  @Test
  void recursiveModelRebuilt() {
    assertThat(content("deep-nesting", "models.py")).contains("Node.model_rebuild()");
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

  @Test
  void pyprojectDeclaresNameVersionAndDependencies() {
    assertThat(pyproject(names("acme-pets")))
        .contains("name = \"acme-pets\"")
        .contains("version = \"1.2.3\"")
        .contains("requires-python = \">=3.9\"")
        .contains("\"httpx>=0.27\"")
        .contains("\"pydantic>=2.8\"")
        .contains("build-backend = \"hatchling.build\"");
  }

  @ParameterizedTest
  @ValueSource(strings = {"Petstore", "Petstore Client"})
  void clientNameEndsWithClientSuffixOnce(String title) {
    assertThat(clientSource(title, List.of())).contains("class PetstoreClient:");
  }

  @Test
  void apiKeyIsSentAtItsDeclaredLocationForEachScheme() {
    // auth-schemes declares both a header and a query apiKey scheme.
    assertThat(content("auth-schemes", "client.py"))
        .contains("\"headerKey\": (\"header\", \"X-API-Key\"),")
        .contains("\"queryKey\": (\"query\", \"api_key\"),");
  }

  @Test
  void apiKeyFallsBackToTheQueryParameterWithoutAHeaderScheme() {
    var queryKey =
        new SecurityScheme("queryKey", SecurityScheme.Kind.API_KEY, "query", "api_key", null);
    assertThat(clientSource(Map.of("queryKey", queryKey)))
        .contains("\"queryKey\": (\"query\", \"api_key\"),")
        .doesNotContain(": (\"header\"");
  }

  @Test
  void basicOptionIsAPlainTupleAndBearerWins() {
    assertThat(clientSource("auth-schemes"))
        .contains("basic: Optional[Tuple[str, str]] = None,")
        .contains("headers.setdefault(\"Authorization\", _basic_credentials(*self._basic))")
        .contains("\"basicAuth\": (\"basic\", \"\"),");
  }

  @Test
  void operationSecurityDecidesWhichCredentialsAreSent() {
    // auth-schemes: a global bearer scheme and operations that override or drop it.
    assertThat(content("auth-schemes", "resources/default.py"))
        .contains("security=[\"headerKey\"]")
        .contains("security=[\"queryKey\"]")
        .contains("security=[\"basicAuth\"]")
        .contains("security=[\"oauth\"]")
        .contains("security=[]")
        .contains("security=[\"bearerAuth\"]");
  }

  @Test
  void bookshelfOperationsSendTheSchemesOfTheirRequirement() {
    assertThat(content("bookshelf", "resources/authors.py")).contains("security=[]");
    assertThat(content("bookshelf", "resources/books.py")).contains("security=[\"bearerAuth\"]");
    assertThat(content("bookshelf", "resources/loans.py"))
        .contains("security=[\"apiKeyAuth\", \"oauth2\"]");
    assertThat(content("bookshelf", "client.py"))
        .contains("\"apiKeyAuth\": (\"header\", \"X-Api-Key\"),")
        .contains("\"bearerAuth\": (\"bearer\", \"\"),")
        .contains("\"oauth2\": (\"bearer\", \"\"),");
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

  @ParameterizedTest
  @ValueSource(strings = {".emitkit/", ".emitkit", ".emitkit//", ""})
  void outputDirIsNormalisedToOneTrailingSlash(String outputDir) {
    String root = outputDir.isEmpty() ? "sdk/python/" : ROOT;
    assertThat(generate("petstore", outputDir, names("acme-pets")))
        .extracting(GeneratedFile::path)
        .allSatisfy(path -> assertThat(path).startsWith(root))
        .contains(root + "acme_pets/client.py");
  }

  @Test
  void asyncClientNamesItselfInItsErrors() {
    assertThat(clientSource("Petstore", List.of()))
        .contains("class AsyncPetstoreClient:")
        .contains("\"AsyncPetstoreClient: base_url is required. The API declares no absolute ")
        .contains("\"AsyncPetstoreClient: max_retries must be a non-negative integer\"")
        .contains("async def aclose(self) -> None:");
  }

  @Test
  void clientsFollowRedirectsLikeTheTypeScriptFetchClient() {
    assertThat(clientSource("Petstore", List.of()))
        .contains("httpx.Client(follow_redirects=True)")
        .contains("httpx.AsyncClient(follow_redirects=True)");
  }

  @ParameterizedTest
  @EnumSource(
      value = SecurityScheme.Kind.class,
      names = {"OAUTH2", "OPENID_CONNECT"})
  void bearerOnlyApiGetsABearerTokenOption(SecurityScheme.Kind kind) {
    var scheme = new SecurityScheme("bearer", kind, null, null, null);
    assertThat(clientSource(Map.of("bearer", scheme)))
        .contains("bearer_token: Optional[str] = None,")
        .contains("\"bearer\": (\"bearer\", \"\"),")
        .contains("headers[\"Authorization\"] = f\"Bearer {self._bearer_token}\"");
  }

  @Test
  void basicOnlyImportsAreOmittedWithoutABasicScheme() {
    assertThat(clientSource("Petstore", List.of()))
        .doesNotContain("import base64")
        .doesNotContain("basic: Optional");
  }

  @Test
  void defaultBaseUrlIsOnlyAnAbsoluteFirstServer() {
    assertThat(clientSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("DEFAULT_BASE_URL = \"https://api.example.com/v1\"");
    // The parser reports a relative "/" server when the document declares none.
    assertThat(clientSource("Petstore", List.of(new Server("/", null))))
        .contains("DEFAULT_BASE_URL = \"\"");
  }

  @Test
  void readmeStatesTheDefaultBaseUrlOrTheRelativeServer() {
    assertThat(readmeSource("Petstore", List.of(new Server("https://api.example.com/v1", null))))
        .contains("Requests go to `https://api.example.com/v1` unless `base_url` is passed.");
    assertThat(readmeSource("Petstore", List.of(new Server("/api/v3", null))))
        .contains("The first server, /api/v3, is not an absolute http(s) URL.");
  }

  @Test
  void quoteEscapesQuotesBackslashesControlCharactersAndSeparators() {
    assertThat(PyClientView.quote("a\"b\\c\nd\t\r\u0001\u007F\u2028"))
        .isEqualTo("\"a\\\"b\\\\c\\nd\\t\\r\\u0001\\u007F\\u2028\"");
  }

  @Test
  void clientAttachesOneResourcePerTag() {
    assertThat(content("bookshelf", "client.py"))
        .contains("from .resources.authors import AsyncAuthorsResource, AuthorsResource")
        .contains("from .resources.books import AsyncBooksResource, BooksResource")
        .contains("from .resources.loans import AsyncLoansResource, LoansResource")
        .contains("        self.books = BooksResource(self)")
        .contains("        self.authors = AuthorsResource(self)")
        .contains("        self.loans = LoansResource(self)")
        .contains("        self.books = AsyncBooksResource(self)")
        .contains("        self.authors = AsyncAuthorsResource(self)")
        .contains("        self.loans = AsyncLoansResource(self)");
  }

  @Test
  void readmeShowsACallThroughAResource() {
    assertThat(sdkFile(FixtureSpecs.load("bookshelf"), "README.md"))
        .contains("client.books.list_books()");
  }

  @Test
  void packageIsMarkedAsTyped() {
    assertThat(generate("petstore", ".emitkit/", names("acme-pets")))
        .filteredOn(f -> f.path().equals(ROOT + "acme_pets/py.typed"))
        .singleElement()
        .satisfies(f -> assertThat(f.content()).isEmpty());
  }

  @Test
  void modelsAndEnumsAreReExportedFromThePackage() {
    assertThat(content("bookshelf", "__init__.py"))
        .contains("from .models import (")
        .contains("\n    Book,\n")
        .contains("\n    BookFormat,\n")
        .contains("\n    Genre,\n")
        .contains("\n    \"Book\",\n")
        .contains("\n    \"Genre\",\n");
  }

  @Test
  void docstringKeepsTheDescriptionAfterTheSummaryWhenTheyDiffer() {
    assertThat(PyResources.doc("List books", "Returns a page of books.\nOf many."))
        .isEqualTo("List books\n\n        Returns a page of books.\n        Of many.");
    assertThat(PyResources.doc("List books", "List  books ")).isEqualTo("List books");
    assertThat(PyResources.doc("List books", null)).isEqualTo("List books");
    assertThat(PyResources.doc(null, "Only a description.")).isEqualTo("Only a description.");
    assertThat(PyResources.doc(null, null)).isEmpty();
  }

  @Test
  void bookshelfMethodDocumentsSummaryAndDescription() {
    assertThat(content("bookshelf", "resources/books.py"))
        .contains(
            "\"\"\"List books\n\n        Returns a page of books, optionally filtered by genre and "
                + "availability.\"\"\"");
  }

  @Test
  void retriesAreLimitedToRepeatableRequestsAndHonourRetryAfter() {
    assertThat(clientSource("Petstore", List.of()))
        .contains(
            "_IDEMPOTENT_METHODS = frozenset({\"GET\", \"HEAD\", \"PUT\", \"DELETE\", \"OPTIONS\"})")
        .contains("MAX_RETRY_AFTER_SECONDS = 30")
        .contains("def _may_retry(")
        .contains("def _retry_delay(")
        .contains("def _parse_retry_after(")
        .contains("return RETRY_BASE_DELAY_SECONDS * float(2**attempt)")
        .contains("not _may_retry(method, request_headers, response.status_code)")
        .contains("await asyncio.sleep(delay)");
  }

  @Test
  void requiredHeaderIsAKeywordArgumentSentAsAHeader() {
    assertThat(content("bookshelf", "resources/books.py"))
        .contains("def create_book(self, *, idempotency_key: str, body: BookInput) -> Book:")
        .contains("headers={\"Idempotency-Key\": idempotency_key}");
  }

  @Test
  void optionalCookieIsAKeywordArgumentSentAsACookie() {
    assertThat(content("bookshelf", "resources/loans.py"))
        .contains("session: Optional[str] = None")
        .contains("cookies={\"session\": session}");
  }

  @Test
  void clientSendsHeaderAndCookieParameters() {
    assertThat(content("bookshelf", "client.py"))
        .contains("headers: Optional[Mapping[str, Any]] = None,")
        .contains("cookies: Optional[Mapping[str, Any]] = None,")
        .contains("quote(_header_text(item), safe=\"\")");
  }

  /** The module-local spec {@code specs/apikeys.yaml}, parsed like a shared fixture. */
  private static ApiModel localSpec(String name) {
    try (var in = PythonSdkGeneratorTest.class.getResourceAsStream("/specs/" + name + ".yaml")) {
      ParseResult result =
          new SpecParser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      if (result.model() == null || !result.errors().isEmpty()) {
        throw new IllegalStateException(name + " did not parse: " + result.errors());
      }
      return result.model();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void localSpecMatchesGolden() {
    GoldenFiles.assertMatches("apikeys", generator.generate(localSpec("apikeys"), ctx()));
  }

  @Test
  void apiKeyIsSentOnceFromTheFirstHeaderOrCookieSchemeNeverFromTheUrlThen() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/resources/keys.py"))
        .contains("security=[\"queryKey\", \"headerKey\"]")
        .contains("security=[\"queryKey\", \"cookieKey\"]")
        .contains("security=[\"queryKey\"]");
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/client.py"))
        .contains("\"headerKey\": (\"header\", \"X-Api-Key\"),")
        .contains("\"cookieKey\": (\"cookie\", \"sid\"),")
        .contains("\"queryKey\": (\"query\", \"api_key\"),")
        .contains("def _api_key_location(");
  }

  @Test
  void reservedHeaderParametersAreNotArguments() {
    String resources = sdkFile(localSpec("apikeys"), "acme_pets/resources/keys.py");
    assertThat(resources)
        .doesNotContain("accept")
        .doesNotContain("authorization")
        .doesNotContain("content_type")
        .doesNotContain("\"Accept\"")
        .doesNotContain("\"Authorization\"")
        .doesNotContain("\"Content-Type\"");
  }

  @Test
  void collidingHeaderAndQueryParametersGetTheLocationPrefix() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/resources/keys.py"))
        .contains("*, limit: Optional[int] = None, header_limit: Optional[str] = None")
        .contains("headers={\"limit\": header_limit}")
        .contains("query=_drop_none({\"limit\": limit})");
  }

  @Test
  void arrayHeadersAndCookiesAreSentAsLists() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/resources/keys.py"))
        .contains("x_tags: list[str]")
        .contains("headers={\"X-Tags\": x_tags}")
        .contains("cookies={\"prefs\": prefs}");
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/client.py")).contains("\",\".join(");
  }

  @Test
  void collidingModelNamesAreExportedUnderAnAlias() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/__init__.py"))
        .contains("    ApiError as ApiErrorModel,")
        .contains("    ApiKeysClient as ApiKeysClientModel,")
        .contains("    \"ApiErrorModel\",")
        .contains("    \"ApiKeysClientModel\",")
        .contains("_custom_exports");
  }

  @Test
  void customClashIsOnlyAnObjectDifferentFromTheGeneratedOne() {
    // custom/ re-exporting a generated model (from ..models import Book) is valid; only a
    // different object bound under a generated name is a clash. The check reads the bound globals,
    // so it must run after the imports that bind them.
    assertThat(content("bookshelf", "__init__.py"))
        .contains("if getattr(_custom, n) is not globals()[n]")
        .doesNotContain("_clashes = sorted(_generated_names & _custom_exports)");
  }

  @Test
  void credentialsWinOverAQueryParameterOfTheSameName() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/client.py"))
        .contains("params: Dict[str, Any] = {**(query or {}), **auth_params}")
        .contains(
            "request_headers = _request_headers(headers, cookies, auth_headers, auth_cookies)");
  }

  @Test
  void requestBodyIsDumpedThroughAnAdapterTypedAsAny() {
    // pydantic 2.8's stubs type an adapter for a union as Never, which rejects the body under
    // mypy --strict. The body is only serialised, so the adapter is typed as Any.
    assertThat(content("bookshelf", "resources/books.py"))
        .contains("TypeAdapter[Any](BookInput).dump_python(body, mode=\"json\"")
        .doesNotContain("TypeAdapter(BookInput).dump_python(body");
  }

  @Test
  void resourceModuleImportsItsClientUnderAnAliasSoAModelCanShareItsName() {
    assertThat(sdkFile(localSpec("apikeys"), "acme_pets/resources/keys.py"))
        .contains(
            "from ..client import ApiKeysClient as _ApiKeysClient, "
                + "AsyncApiKeysClient as _AsyncApiKeysClient")
        .contains("def __init__(self, client: _ApiKeysClient) -> None:")
        .contains("-> ApiKeysClient:");
  }

  @Test
  void aliasesAreValidationAndSerializationAliases() {
    assertThat(content("reserved-words", "models.py"))
        .contains("from_: ")
        .contains("validation_alias=\"from\", serialization_alias=\"from\"")
        .doesNotContain(" alias=\"from\"");
  }

  @Test
  void discriminatorFieldsDefaultToTheirValueAndKeepItOnDump() {
    assertThat(content("bookshelf", "models.py"))
        .contains("    kind: Literal[\"print\"] = \"print\"")
        .contains("@model_validator(mode=\"before\")")
        .contains("data = {\"kind\": \"print\", **data}");
  }

  @Test
  void literalStaysOnTheOptionsOfADiscriminatedUnion() {
    // Stray is not an option of BookFormat, so its single-value kind keeps its enum class.
    var kind = new EnumSchema(PrimitiveType.STRING, List.of("x"), false, null);
    var stray = new ObjectSchema(Map.of("kind", kind), Set.of("kind"), null, false, null);
    assertThat(
            sdkFile(
                extended(FixtureSpecs.load("bookshelf"), null, Map.of("Stray", stray)),
                "acme_pets/models.py"))
        .contains("class StrayKind(str, Enum):")
        .contains("    kind: StrayKind")
        .contains("    kind: Literal[\"print\"]");
  }

  @Test
  void optionalDiscriminatorLeavesTheUnionPlain() {
    var kind = new EnumSchema(PrimitiveType.STRING, List.of("c"), false, null);
    var probe1 = new ObjectSchema(Map.of("kind", kind), Set.of(), null, false, null);
    var probe2 =
        new ObjectSchema(
            Map.of("kind", new EnumSchema(PrimitiveType.STRING, List.of("d"), false, null)),
            Set.of("kind"),
            null,
            false,
            null);
    var probes =
        new OneOfSchema(
            List.of(new RefSchema("Probe1", false, null), new RefSchema("Probe2", false, null)),
            "kind",
            false,
            null);
    assertThat(
            sdkFile(
                extended(
                    FixtureSpecs.load("bookshelf"),
                    null,
                    Map.of("Probe1", probe1, "Probe2", probe2, "Probes", probes)),
                "acme_pets/models.py"))
        .contains("Probes = Union[Probe1, Probe2]")
        .doesNotContain("Probes = Annotated");
  }

  @Test
  void duplicateDiscriminatorValuesLeaveTheUnionPlain() {
    var probe1 =
        new ObjectSchema(
            Map.of("kind", new EnumSchema(PrimitiveType.STRING, List.of("c"), false, null)),
            Set.of("kind"),
            null,
            false,
            null);
    var probe2 =
        new ObjectSchema(
            Map.of("kind", new EnumSchema(PrimitiveType.STRING, List.of("c"), false, null)),
            Set.of("kind"),
            null,
            false,
            null);
    var probes =
        new OneOfSchema(
            List.of(new RefSchema("Probe1", false, null), new RefSchema("Probe2", false, null)),
            "kind",
            false,
            null);
    assertThat(
            sdkFile(
                extended(
                    FixtureSpecs.load("bookshelf"),
                    null,
                    Map.of("Probe1", probe1, "Probe2", probe2, "Probes", probes)),
                "acme_pets/models.py"))
        .contains("Probes = Union[Probe1, Probe2]")
        .doesNotContain("Probes = Annotated");
  }

  /** A file of the {@code acme_pets} package generated from the named fixture. */
  private String content(String fixture, String relative) {
    return content(generate(fixture, ".emitkit/", names("acme-pets")), "acme_pets/" + relative);
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
