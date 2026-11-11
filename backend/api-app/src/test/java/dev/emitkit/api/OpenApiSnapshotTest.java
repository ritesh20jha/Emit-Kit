package dev.emitkit.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import tools.jackson.core.util.Separators;
import tools.jackson.core.util.Separators.Spacing;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the served OpenAPI document to frontend/openapi.json, the input of the generated TypeScript
 * client. Run with -Dopenapi.update=true (see scripts/export-openapi.sh) to rewrite the file
 * instead of comparing. Both paths use the same normalizer, so the export and the check cannot
 * drift. The other tests pin the shape the frontend relies on.
 */
@SpringBootTest(
    properties = {
      "emitkit.cors.allowed-origins=http://localhost:5173",
      "emitkit.vault.cipher=local"
    })
@AutoConfigureMockMvc
@Import({RedisTestConfig.class, PostgresTestConfig.class})
class OpenApiSnapshotTest {

  /** Gradle runs tests in backend/api-app, so this resolves to the repository's frontend folder. */
  private static final Path COMMITTED = Path.of("../../frontend/openapi.json");

  private static final JsonMapper MAPPER =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  /** Two-space indent, "key": value, one array element per line, LF line ends on every OS. */
  private static final DefaultPrettyPrinter PRINTER =
      new DefaultPrettyPrinter()
          .withObjectIndenter(new DefaultIndenter("  ", "\n"))
          .withArrayIndenter(new DefaultIndenter("  ", "\n"))
          .withSeparators(
              Separators.createDefaultInstance().withObjectNameValueSpacing(Spacing.AFTER));

  @Autowired private MockMvc mvc;

  @Test
  void apiDocsMatchCommittedFile() throws Exception {
    String normalized = normalize(servedDocument());

    if (Boolean.getBoolean("openapi.update")) {
      Files.writeString(COMMITTED, normalized);
      return;
    }
    assertThat(normalized).isEqualTo(Files.readString(COMMITTED));
  }

  /**
   * Every property is either required or nullable. Generated types then never have an optional
   * field the server always sends, and a field the server may send as null is typed as null.
   */
  @Test
  void everyPropertyIsRequiredOrNullable() throws Exception {
    List<String> violations = new ArrayList<>();
    Set<String> schemaNames = new HashSet<>();
    for (Map.Entry<String, JsonNode> schema : apiDocs().at("/components/schemas").properties()) {
      schemaNames.add(schema.getKey());
      JsonNode required = schema.getValue().path("required");
      for (Map.Entry<String, JsonNode> property :
          schema.getValue().path("properties").properties()) {
        boolean isRequired = listed(required, property.getKey());
        boolean isNullable = admitsNull(property.getValue());
        if (!isRequired && !isNullable) {
          violations.add(schema.getKey() + "." + property.getKey());
        }
      }
    }

    assertThat(schemaNames).as("component schemas").contains("MeResponse", "OrgDetail");
    assertThat(violations)
        .as("properties that are neither required nor nullable (schema.property)")
        .isEmpty();
  }

  /**
   * A nullable reference is written as a oneOf with a null branch. A null type next to a $ref is
   * dropped by the generated client, so it would type the field as the reference alone.
   */
  @Test
  void nullableReferencesAreOneOfWithNull() throws Exception {
    JsonNode doc = apiDocs();

    for (String pointer :
        List.of(
            "/components/schemas/ProjectSummary/properties/lastRunStatus",
            "/components/schemas/CreateProjectRequest/properties/visibility",
            "/components/schemas/SaveSpecResponse/properties/runError")) {
      assertThat(doc.at(pointer + "/oneOf").size()).as(pointer + " oneOf branches").isEqualTo(2);
      assertThat(doc.at(pointer + "/oneOf/0/$ref").asString())
          .as(pointer + " reference branch")
          .startsWith("#/components/schemas/");
      assertThat(doc.at(pointer + "/oneOf/1/type").asString())
          .as(pointer + " null branch")
          .isEqualTo("null");
    }
  }

  @Test
  void meResponseNameIsRequiredAndNullable() throws Exception {
    JsonNode name = apiDocs().at("/components/schemas/MeResponse/properties/name");
    JsonNode required = apiDocs().at("/components/schemas/MeResponse/required");

    assertThat(listed(required, "name")).as("MeResponse.name required").isTrue();
    assertThat(listed(name.path("type"), "null")).as("MeResponse.name nullable").isTrue();
  }

  /** The client is built with baseUrl /api/v1, so paths are relative to it. */
  @Test
  void pathsAreRelativeToTheApiBase() throws Exception {
    JsonNode doc = apiDocs();

    assertThat(doc.at("/servers").size()).as("server entries").isEqualTo(1);
    assertThat(doc.at("/servers/0/url").asString()).isEqualTo("/api/v1");
    Set<String> paths = new HashSet<>();
    doc.at("/paths").propertyNames().forEach(paths::add);
    assertThat(paths)
        .containsExactlyInAnyOrder(
            "/me",
            "/me/vcs-quota",
            "/me/sync-orgs",
            "/orgs",
            "/orgs/{orgId}",
            "/orgs/{orgId}/members",
            "/orgs/{orgId}/members/{userId}",
            "/orgs/{orgId}/projects",
            "/orgs/{orgId}/vcs-repos",
            "/orgs/{orgId}/vcs-repos/{owner}/{repo}/spec-check",
            "/projects/{id}",
            "/projects/{id}/webhook",
            "/projects/{id}/config",
            "/projects/{id}/spec",
            "/projects/{id}/spec/snapshots",
            "/projects/{id}/spec/diff",
            "/lint");
  }

  /** Status codes the contract promises beyond the 200 default. */
  @Test
  void documentedStatusesMatchTheContract() throws Exception {
    JsonNode doc = apiDocs();

    assertThat(doc.at("/paths/~1orgs~1{orgId}~1projects/post/responses").propertyNames())
        .contains("201");
    assertThat(doc.at("/paths/~1projects~1{id}/delete/responses").propertyNames()).contains("204");
    assertThat(doc.at("/paths/~1projects~1{id}~1spec/put/responses").propertyNames())
        .contains("200", "202");
  }

  @Test
  void membersOperationsAreTaggedMembers() throws Exception {
    JsonNode doc = apiDocs();

    assertThat(listed(doc.at("/paths/~1orgs~1{orgId}~1members/get/tags"), "Members")).isTrue();
    assertThat(listed(doc.at("/paths/~1orgs~1{orgId}~1members~1{userId}/patch/tags"), "Members"))
        .isTrue();
  }

  @Test
  void responsesAreJson() throws Exception {
    JsonNode content = apiDocs().at("/paths/~1me/get/responses/200/content");

    assertThat(content.propertyNames()).containsExactly("application/json");
  }

  @Test
  void roleAndRoleSourceAreNamedSchemas() throws Exception {
    JsonNode doc = apiDocs();

    assertThat(doc.at("/components/schemas/Role/enum").size()).isEqualTo(3);
    assertThat(doc.at("/components/schemas/RoleSource/enum").size()).isEqualTo(2);
    assertThat(doc.at("/components/schemas/OrgMembership/properties/role/$ref").asString())
        .isEqualTo("#/components/schemas/Role");
  }

  private JsonNode apiDocs() throws Exception {
    return MAPPER.readTree(servedDocument());
  }

  private String servedDocument() throws Exception {
    return mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  /** True when the property admits null: a null type, or a oneOf that has a null branch. */
  private static boolean admitsNull(JsonNode property) {
    if (typeIncludes(property.path("type"), "null")) {
      return true;
    }
    for (JsonNode branch : property.path("oneOf")) {
      if (typeIncludes(branch.path("type"), "null")) {
        return true;
      }
    }
    return false;
  }

  /** True when a JSON type is the value, or an array of types that contains it. */
  private static boolean typeIncludes(JsonNode type, String value) {
    return type.isArray() ? listed(type, value) : type.asString().equals(value);
  }

  /** True when the JSON array contains the value. */
  private static boolean listed(JsonNode array, String value) {
    for (JsonNode item : array) {
      if (item.asString().equals(value)) {
        return true;
      }
    }
    return false;
  }

  /** Sorted keys at every depth, two-space indent, trailing newline: one canonical text. */
  static String normalize(String json) {
    Object tree = MAPPER.readValue(json, Object.class);
    return MAPPER.writer().with(PRINTER).writeValueAsString(tree) + "\n";
  }
}
