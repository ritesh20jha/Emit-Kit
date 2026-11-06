package dev.emitkit.spec.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Server;
import dev.emitkit.spec.model.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpecParserOperationsTest {

  private final SpecParser parser = new SpecParser();

  @ParameterizedTest
  @ValueSource(strings = {"petstore-2.0.yaml", "petstore-3.0.json", "petstore-3.1.yaml"})
  void allVersionsGiveSameOperations(String file) {
    var r = parser.parse(Fixtures.read(file));
    assertThat(r.ok()).as("errors: %s", r.errors()).isTrue();
    assertThat(r.model().operations())
        .extracting(Operation::operationId, Operation::method, Operation::path)
        .containsExactly(
            tuple("listPets", "GET", "/pets"),
            tuple("createPet", "POST", "/pets"),
            tuple("getPet", "GET", "/pets/{petId}"));
  }

  private Operation only(ParseResult r) {
    assertThat(r.ok()).as("errors: %s", r.errors()).isTrue();
    assertThat(r.model().operations()).hasSize(1);
    return r.model().operations().get(0);
  }

  @Test
  void operationLevelParamWinsOnNameAndIn() {
    var op = only(parser.parse(Fixtures.read("path-level-params.yaml")));
    assertThat(op.parameters())
        .extracting(Parameter::name, Parameter::required)
        .containsExactly(tuple("id", true), tuple("limit", true));
  }

  @Test
  void missingOperationId_isSynthesized() {
    var ops = parser.parse(Fixtures.read("no-ids.yaml")).model().operations();
    assertThat(ops).extracting(Operation::operationId).contains("getUsersById", "postUsers");
    assertThat(ops).allMatch(Operation::operationIdSynthesized);
  }

  @Test
  void invalidSpec_returnsErrorsWithoutThrowing() {
    var r = parser.parse(Fixtures.read("invalid.yaml"));
    assertThat(r.ok()).isFalse();
    assertThat(r.errors()).isNotEmpty();
  }

  @Test
  void notYamlAtAll_returnsErrors() {
    assertThat(parser.parse("{{{").errors()).isNotEmpty();
  }

  @Test
  void formatDetected() {
    assertThat(parser.parse(Fixtures.read("petstore-3.0.json")).format()).isEqualTo("json");
  }

  @Test
  void contentHashIsSha256Hex() {
    assertThat(parser.parse("openapi: 3.1.0\n...").contentHash()).hasSize(64);
  }

  @Test
  void mapsInfoServersTagsSecurityAndOperationDetails() {
    var r = parser.parse(Fixtures.read("petstore-3.0.json"));
    var model = r.model();

    assertThat(r.infoVersion()).isEqualTo("1.0.0");
    assertThat(model.info().title()).isEqualTo("Petstore");
    assertThat(model.info().contactEmail()).isEqualTo("api@example.com");
    assertThat(model.servers())
        .containsExactly(new Server("https://petstore.example.com/v1", "Production"));
    assertThat(model.tags()).containsExactly(new Tag("pets", "Pet operations"));
    assertThat(model.securitySchemes())
        .containsEntry(
            "apiKeyAuth",
            new SecurityScheme(
                "apiKeyAuth", SecurityScheme.Kind.API_KEY, "header", "X-API-Key", null));

    var listPets = model.operations().get(0);
    assertThat(listPets.operationIdSynthesized()).isFalse();
    assertThat(listPets.tag()).isEqualTo("pets");
    assertThat(listPets.security()).containsExactly("apiKeyAuth");

    var createPet = model.operations().get(1);
    assertThat(createPet.requestBody()).isNotNull();
    assertThat(createPet.requestBody().required()).isTrue();
    assertThat(createPet.requestBody().contentType()).isEqualTo("application/json");

    var getPet = model.operations().get(2);
    assertThat(getPet.parameters())
        .singleElement()
        .satisfies(p -> {
          assertThat(p.name()).isEqualTo("petId");
          assertThat(p.in()).isEqualTo(ParamLocation.PATH);
          assertThat(p.required()).isTrue();
        });
  }

  @Test
  void resolvesComponentRequestBodiesAndResponses() {
    var r =
        parser.parse(
            """
            openapi: 3.0.3
            info: {title: Refs, version: "1"}
            paths:
              /pets:
                put:
                  operationId: putPets
                  requestBody: {$ref: '#/components/requestBodies/PetBody'}
                  responses:
                    '200': {$ref: '#/components/responses/Ok'}
            components:
              requestBodies:
                PetBody:
                  required: true
                  content:
                    application/json:
                      schema: {type: object}
              responses:
                Ok:
                  description: all good
            """);

    assertThat(r.ok()).as("errors: %s", r.errors()).isTrue();
    var put = r.model().operations().get(0);
    assertThat(put.requestBody()).isNotNull();
    assertThat(put.requestBody().required()).isTrue();
    assertThat(put.requestBody().contentType()).isEqualTo("application/json");
    assertThat(put.responses().get("200").description()).isEqualTo("all good");
  }

  @Test
  void relativeExternalRefIsRejectedWithoutResolving() {
    var r =
        parser.parse(
            """
            openapi: 3.0.3
            info: {title: Ext, version: "1"}
            paths:
              /pets:
                get:
                  operationId: getPets
                  responses:
                    "200": {$ref: 'secret.yaml#/Leaked'}
            """);

    assertThat(r.ok()).isFalse();
    assertThat(r.errors()).containsExactly("external $ref not supported: secret.yaml#/Leaked");
  }

  @Test
  void httpExternalRefIsRejectedWithoutFetching() {
    var r =
        parser.parse(
            """
            openapi: 3.0.3
            info: {title: Ext, version: "1"}
            paths:
              /pets:
                get:
                  operationId: getPets
                  responses:
                    "200": {$ref: 'http://127.0.0.1:1/remote.yaml#/Remote'}
            """);

    assertThat(r.ok()).isFalse();
    assertThat(r.errors())
        .containsExactly("external $ref not supported: http://127.0.0.1:1/remote.yaml#/Remote");
  }

  @Test
  void unresolvedInternalRefIsReported() {
    var r =
        parser.parse(
            """
            openapi: 3.0.3
            info: {title: Dangling, version: "1"}
            paths:
              /pets:
                get:
                  operationId: getPets
                  parameters:
                    - {$ref: '#/components/parameters/Nope'}
                  responses:
                    "200": {description: ok}
            """);

    assertThat(r.ok()).isFalse();
    assertThat(r.errors()).containsExactly("unresolved $ref: #/components/parameters/Nope");
  }

  @Test
  void synthesizedIdDoesNotCollideWithExplicitId() {
    var ops =
        parser
            .parse(
                """
                openapi: 3.0.3
                info: {title: Dup, version: "1"}
                paths:
                  /users/by-id:
                    get:
                      operationId: getUsersById
                      responses:
                        "200": {description: ok}
                  /users/{id}:
                    get:
                      responses:
                        "200": {description: ok}
                """)
            .model()
            .operations();

    assertThat(ops)
        .extracting(Operation::operationId, Operation::operationIdSynthesized)
        .containsExactly(tuple("getUsersById", false), tuple("getUsersById2", true));
  }

  @Test
  void unscannableDocumentFailsClosedWithoutSwagger() {
    // Past the YAML reader's size limit, so the $ref pre-scan cannot read it.
    String pad = "x".repeat(3_300_000);
    var r =
        parser.parse(
            "{\"openapi\": \"3.0.3\", \"info\": {\"title\": \"Big\", \"version\": \"1\"},"
                + " \"x-pad\": \""
                + pad
                + "\", \"paths\": {\"/pets\": {\"get\": {\"operationId\": \"getPets\","
                + " \"responses\": {\"200\": {\"$ref\": \"secret.yaml#/Leaked\"}}}}}}");

    assertThat(r.ok()).isFalse();
    assertThat(r.model()).isNull();
    assertThat(r.errors()).hasSize(1);
    assertThat(r.errors().get(0)).startsWith("could not read document");
  }
}
