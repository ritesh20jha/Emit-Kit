package dev.emitkit.spec.diff;

import static dev.emitkit.spec.diff.ChangeKind.ENUM_VALUE_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.ENUM_VALUE_REMOVED;
import static dev.emitkit.spec.diff.ChangeKind.REQUEST_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.REQUEST_REQUIRED_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.RESPONSE_PROPERTY_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.RESPONSE_PROPERTY_REMOVED;
import static dev.emitkit.spec.diff.ChangeKind.TYPE_CHANGED;
import static dev.emitkit.spec.diff.ModelBuilder.enumOf;
import static dev.emitkit.spec.diff.ModelBuilder.integer;
import static dev.emitkit.spec.diff.ModelBuilder.jsonBody;
import static dev.emitkit.spec.diff.ModelBuilder.model;
import static dev.emitkit.spec.diff.ModelBuilder.object;
import static dev.emitkit.spec.diff.ModelBuilder.op;
import static dev.emitkit.spec.diff.ModelBuilder.ref;
import static dev.emitkit.spec.diff.ModelBuilder.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import dev.emitkit.spec.diff.ModelBuilder.Op;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.Schema;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaDiffTest {

  private static final Op createPet =
      op("createPet", "POST", "/pets").withBody(jsonBody(ref("Pet")));
  private static final Op getPet =
      op("getPet", "GET", "/pets/{id}").withResponse("200", ref("Pet"));

  private final SpecDiffer differ = new SpecDiffer(new DefaultSchemaDiffer());

  /** Every change in the diff, flattened across operations. */
  private List<Change> changes(ApiModel before, ApiModel after) {
    return differ.diff(before, after).modified().stream()
        .flatMap(m -> m.changes().stream())
        .toList();
  }

  @Test
  void requiredRequestPropertyAdded_isBreaking() {
    var before = model(Map.of("Pet", object(Map.of("name", string()))), createPet);
    var after =
        model(Map.of("Pet", object(Map.of("name", string(), "tag", string()), "tag")), createPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(REQUEST_REQUIRED_PROPERTY_ADDED, "body.tag", true));
  }

  @Test
  void optionalRequestPropertyAdded_notBreaking() {
    var before = model(Map.of("Pet", object(Map.of("name", string()))), createPet);
    var after = model(Map.of("Pet", object(Map.of("name", string(), "tag", string()))), createPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(REQUEST_PROPERTY_ADDED, "body.tag", false));
  }

  @Test
  void responsePropertyRemoved_isBreaking() {
    var before = model(Map.of("Pet", object(Map.of("id", string(), "name", string()))), getPet);
    var after = model(Map.of("Pet", object(Map.of("id", string()))), getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(RESPONSE_PROPERTY_REMOVED, "200.name", true));
  }

  @Test
  void responsePropertyAdded_notBreaking() {
    var before = model(Map.of("Pet", object(Map.of("id", string()))), getPet);
    var after = model(Map.of("Pet", object(Map.of("id", string(), "nickname", string()))), getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(RESPONSE_PROPERTY_ADDED, "200.nickname", false));
  }

  @Test
  void enumValueRemoved_isBreaking() {
    var before =
        model(Map.of("Pet", object(Map.of("status", enumOf("available", "sold")))), getPet);
    var after = model(Map.of("Pet", object(Map.of("status", enumOf("available")))), getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(ENUM_VALUE_REMOVED, "200.status", true));
  }

  @Test
  void typeChanged_isBreaking() {
    var before = model(Map.of("Pet", object(Map.of("id", integer()))), getPet);
    var after = model(Map.of("Pet", object(Map.of("id", string()))), getPet);
    assertThat(changes(before, after)).containsExactly(new Change(TYPE_CHANGED, "200.id", true));
  }

  @Test
  void changeInsideRefIsFound() {
    var before =
        model(
            Map.of(
                "Pet", object(Map.of("owner", ref("Owner"))),
                "Owner", object(Map.of("address", ref("Address"))),
                "Address", object(Map.of("zip", string(), "city", string()))),
            getPet);
    var after =
        model(
            Map.of(
                "Pet", object(Map.of("owner", ref("Owner"))),
                "Owner", object(Map.of("address", ref("Address"))),
                "Address", object(Map.of("city", string()))),
            getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(RESPONSE_PROPERTY_REMOVED, "200.owner.address.zip", true));
  }

  @Test
  void recursiveSchemaTerminates() {
    // Pet refers to itself; the change is reported once, at the top level, and the walk stops.
    var before =
        model(Map.of("Pet", object(Map.of("value", string(), "next", ref("Pet")))), getPet);
    var after =
        model(
            Map.of("Pet", object(Map.of("value", string(), "next", ref("Pet"), "label", string()))),
            getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(RESPONSE_PROPERTY_ADDED, "200.label", false));
  }

  @Test
  void defaultSpecDiffer_usesSchemaRules() {
    var before = model(Map.of("Pet", object(Map.of("id", string(), "name", string()))), getPet);
    var after = model(Map.of("Pet", object(Map.of("id", string()))), getPet);
    assertThat(new SpecDiffer().diff(before, after).modified())
        .singleElement()
        .satisfies(m ->
            assertThat(m.changes())
                .containsExactly(new Change(RESPONSE_PROPERTY_REMOVED, "200.name", true)));
  }

  @Test
  void denseMutualRefs_terminatesQuickly() {
    // S0..S11 each refer to every other schema. S3 loses its leaf, which must be reported once.
    int count = 12;
    var before = new LinkedHashMap<String, Schema>();
    var after = new LinkedHashMap<String, Schema>();
    for (int i = 0; i < count; i++) {
      var beforeProps = new LinkedHashMap<String, Schema>();
      var afterProps = new LinkedHashMap<String, Schema>();
      for (int j = 0; j < count; j++) {
        if (j != i) {
          beforeProps.put("r" + j, ref("S" + j));
          afterProps.put("r" + j, ref("S" + j));
        }
      }
      beforeProps.put("leaf", string());
      if (i != 3) {
        afterProps.put("leaf", string());
      }
      before.put("S" + i, object(beforeProps));
      after.put("S" + i, object(afterProps));
    }
    var getS = op("getS", "GET", "/s").withResponse("200", ref("S0"));
    var beforeModel = model(before, getS);
    var afterModel = model(after, getS);

    var changes =
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> changes(beforeModel, afterModel));

    // Reached first through S0.r1.r2.r3; later paths to S3 are skipped as already visited.
    assertThat(changes)
        .containsExactly(new Change(RESPONSE_PROPERTY_REMOVED, "200.r1.r2.r3.leaf", true));
  }

  @Test
  void requestPropertyBecameRequired_isBreaking() {
    var before = model(Map.of("Pet", object(Map.of("name", string()))), createPet);
    var after = model(Map.of("Pet", object(Map.of("name", string()), "name")), createPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(REQUEST_REQUIRED_PROPERTY_ADDED, "body.name", true));

    // The same optional-to-required change on the response side is not reported.
    var responseBefore = model(Map.of("Pet", object(Map.of("name", string()))), getPet);
    var responseAfter = model(Map.of("Pet", object(Map.of("name", string()), "name")), getPet);
    assertThat(changes(responseBefore, responseAfter)).isEmpty();
  }

  @Test
  void mapValueTypeChanged_isBreaking() {
    var before = model(Map.of("Pet", mapOf(integer())), getPet);
    var after = model(Map.of("Pet", mapOf(string())), getPet);
    assertThat(changes(before, after)).containsExactly(new Change(TYPE_CHANGED, "200", true));
  }

  @Test
  void requestPropertyTypeChanged_isBreaking() {
    var before = model(Map.of("Pet", object(Map.of("id", integer()))), createPet);
    var after = model(Map.of("Pet", object(Map.of("id", string()))), createPet);
    assertThat(changes(before, after)).containsExactly(new Change(TYPE_CHANGED, "body.id", true));
  }

  @Test
  void requestEnumValueRemoved_isBreaking() {
    var before =
        model(Map.of("Pet", object(Map.of("status", enumOf("available", "sold")))), createPet);
    var after = model(Map.of("Pet", object(Map.of("status", enumOf("available")))), createPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(ENUM_VALUE_REMOVED, "body.status", true));
  }

  @Test
  void enumValueAdded_notBreaking() {
    var before = model(Map.of("Pet", object(Map.of("status", enumOf("available")))), getPet);
    var after = model(Map.of("Pet", object(Map.of("status", enumOf("available", "sold")))), getPet);
    assertThat(changes(before, after))
        .containsExactly(new Change(ENUM_VALUE_ADDED, "200.status", false));
  }

  /** An object with no fixed properties whose values have the given schema. */
  private static Schema mapOf(Schema values) {
    return new ObjectSchema(Map.of(), Set.of(), values, false, null);
  }
}
