package dev.emitkit.spec.diff;

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

import dev.emitkit.spec.diff.ModelBuilder.Op;
import dev.emitkit.spec.model.ApiModel;
import java.util.List;
import java.util.Map;
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
}
