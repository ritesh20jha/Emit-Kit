package dev.emitkit.gen.core.fixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.ParamLocation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.model.SecurityScheme;
import dev.emitkit.spec.model.Tag;
import dev.emitkit.spec.parse.SpecParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

class FixtureSpecsTest {

  @ParameterizedTest
  @FieldSource("dev.emitkit.gen.core.fixtures.FixtureSpecs#ALL")
  void parsesWithoutErrors(String name) {
    var r = new SpecParser().parse(FixtureSpecs.raw(name));
    assertThat(r.errors()).isEmpty();
    assertThat(r.ok()).isTrue();
  }

  @Test
  void big200HasTwoHundredOps() {
    ApiModel model = FixtureSpecs.load("big-200-ops");
    assertThat(model.operations()).hasSize(200);
    assertThat(model.tags()).hasSize(20);
    assertThat(model.schemas()).hasSize(40);
  }

  @Test
  void reservedWordsFixtureHasCollidingTags() {
    assertThat(FixtureSpecs.load("reserved-words").tags())
        .extracting(Tag::name)
        .contains("user", "User");
  }

  @Test
  void authSchemesCoverEveryKind() {
    var schemes = FixtureSpecs.load("auth-schemes").securitySchemes().values();
    assertThat(schemes)
        .extracting(SecurityScheme::kind)
        .contains(
            SecurityScheme.Kind.API_KEY, SecurityScheme.Kind.HTTP, SecurityScheme.Kind.OAUTH2);
    assertThat(schemes)
        .filteredOn(s -> s.kind() == SecurityScheme.Kind.API_KEY)
        .extracting(SecurityScheme::in)
        .containsExactlyInAnyOrder("header", "query");
    assertThat(schemes)
        .filteredOn(s -> s.kind() == SecurityScheme.Kind.HTTP)
        .extracting(SecurityScheme::scheme)
        .containsExactlyInAnyOrder("bearer", "basic");
  }

  @Test
  void authSchemesHavePerOperationOverrides() {
    var ops = FixtureSpecs.load("auth-schemes").operations();
    assertThat(ops)
        .extracting(Operation::operationId, Operation::security)
        .containsExactlyInAnyOrder(
            tuple("getWithHeaderKey", List.of("headerKey")),
            tuple("getWithQueryKey", List.of("queryKey")),
            tuple("getWithBasic", List.of("basicAuth")),
            tuple("getWithOAuth", List.of("oauth")),
            tuple("getPublic", List.of()),
            tuple("getInherited", List.of("bearerAuth")));
  }

  @Test
  void polymorphismCoversUnionsInheritanceAndNullableRefs() {
    var schemas = FixtureSpecs.load("polymorphism").schemas().values();
    var unions =
        schemas.stream()
            .filter(OneOfSchema.class::isInstance)
            .map(OneOfSchema.class::cast)
            .toList();
    assertThat(unions).anyMatch(u -> u.discriminator() != null);
    assertThat(unions).anyMatch(u -> u.discriminator() == null);
    assertThat(schemas).anyMatch(AllOfSchema.class::isInstance);
    assertThat(schemas).anyMatch(s -> s instanceof RefSchema ref && ref.nullable());
  }

  @Test
  void reservedWordsFixtureKeepsReservedNames() {
    var model = FixtureSpecs.load("reserved-words");
    assertThat(model.operations())
        .extracting(Operation::operationId)
        .contains("class", "delete", "import", "default");
    assertThat(model.schemas().keySet()).contains("Error", "Object", "String", "None");
    assertThat(model.operations())
        .flatExtracting(Operation::parameters)
        .filteredOn(p -> p.in() == ParamLocation.PATH)
        .extracting(Parameter::name)
        .contains("in");
    assertThat(propertyNames(model.schemas().values())).contains("type", "from", "self", "2fa");
  }

  @Test
  void paginationHasCursorAndLimitQueryParams() {
    var ops = FixtureSpecs.load("pagination").operations();
    assertThat(ops)
        .flatExtracting(Operation::parameters)
        .extracting(Parameter::name, Parameter::in)
        .contains(tuple("cursor", ParamLocation.QUERY), tuple("limit", ParamLocation.QUERY));
    // The model does not expose response headers, so the Link header is checked in the source.
    assertThat(FixtureSpecs.raw("pagination")).contains("Link:");
  }

  @Test
  void deepNestingHasRecursiveNodeAndArraysOfArrays() {
    var schemas = FixtureSpecs.load("deep-nesting").schemas();
    Schema node = schemas.get("Node");
    assertThat(node).isInstanceOf(ObjectSchema.class);
    assertThat(((ObjectSchema) node).properties().get("children"))
        .isInstanceOfSatisfying(
            ArraySchema.class,
            children -> assertThat(children.items()).isEqualTo(new RefSchema("Node", false, null)));
    assertThat(schemas.values())
        .anyMatch(s -> s instanceof ArraySchema outer && outer.items() instanceof ArraySchema);
    assertThat(maxObjectDepth(schemas.get("Deep"), 0)).isGreaterThanOrEqualTo(5);
  }

  @Test
  void enumsFixtureHasEnumSchemas() {
    assertThat(FixtureSpecs.load("enums").schemas().values())
        .anyMatch(EnumSchema.class::isInstance);
  }

  @Test
  void noOperationIdsAreSynthesized() {
    var ops = FixtureSpecs.load("no-operation-ids").operations();
    assertThat(ops).isNotEmpty().allMatch(Operation::operationIdSynthesized);
  }

  @Test
  void multiTagOperationsKeepFirstTag() {
    var model = FixtureSpecs.load("multi-tag");
    assertThat(model.tags()).extracting(Tag::name).contains("pets", "store", "admin");
    assertThat(model.operations())
        .extracting(Operation::tag)
        .containsExactlyInAnyOrder("pets", "admin");
  }

  private static List<String> propertyNames(Collection<Schema> schemas) {
    var names = new ArrayList<String>();
    for (Schema schema : schemas) {
      if (schema instanceof ObjectSchema object) {
        names.addAll(object.properties().keySet());
      }
    }
    return names;
  }

  /** Depth of nested inline objects below {@code schema}; refs are not followed. */
  private static int maxObjectDepth(Schema schema, int depth) {
    if (!(schema instanceof ObjectSchema object)) {
      return depth;
    }
    int deepest = depth + 1;
    for (Schema property : object.properties().values()) {
      deepest = Math.max(deepest, maxObjectDepth(property, depth + 1));
    }
    return deepest;
  }
}
