package dev.emitkit.spec.diff;

import static dev.emitkit.spec.diff.ChangeKind.BODY_BECAME_REQUIRED;
import static dev.emitkit.spec.diff.ChangeKind.DESCRIPTION_CHANGED;
import static dev.emitkit.spec.diff.ChangeKind.PARAM_ADDED;
import static dev.emitkit.spec.diff.ChangeKind.PARAM_BECAME_OPTIONAL;
import static dev.emitkit.spec.diff.ChangeKind.PARAM_BECAME_REQUIRED;
import static dev.emitkit.spec.diff.ChangeKind.PARAM_REMOVED;
import static dev.emitkit.spec.diff.ModelBuilder.jsonBody;
import static dev.emitkit.spec.diff.ModelBuilder.model;
import static dev.emitkit.spec.diff.ModelBuilder.op;
import static dev.emitkit.spec.diff.ModelBuilder.pathParam;
import static dev.emitkit.spec.diff.ModelBuilder.query;
import static dev.emitkit.spec.diff.ModelBuilder.string;
import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.spec.diff.ModelBuilder.Op;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Schema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OperationDiffTest {

  private static final Op listPets = op("listPets", "GET", "/pets");
  private static final Op getPet = op("getPet", "GET", "/pets/{id}");

  private final SpecDiffer differ = new SpecDiffer();

  private SpecDiff diff(ApiModel before, ApiModel after) {
    return differ.diff(before, after);
  }

  /** Diffs a single operation pair and returns the one change it produces. */
  private Change onlyChange(Op before, Op after) {
    var d = diff(model(before), model(after));
    assertThat(d.modified()).hasSize(1);
    assertThat(d.modified().get(0).changes()).hasSize(1);
    return d.modified().get(0).changes().get(0);
  }

  @Test
  void operationRemoved_isBreaking() {
    var d = diff(model(listPets, getPet), model(listPets));
    assertThat(d.removed()).extracting(OpRef::operationId).containsExactly("getPet");
    assertThat(d.hasBreaking()).isTrue();
  }

  @Test
  void operationAdded_notBreaking() {
    var d = diff(model(listPets), model(listPets, getPet));
    assertThat(d.added()).hasSize(1);
    assertThat(d.hasBreaking()).isFalse();
  }

  @Test
  void sameOperationIdWithNewPath_isRemovedAndAdded() {
    var d = diff(model(op("listPets", "GET", "/pets")), model(op("listPets", "GET", "/v2/pets")));
    assertThat(d.removed()).containsExactly(new OpRef("listPets", "GET", "/pets"));
    assertThat(d.added()).containsExactly(new OpRef("listPets", "GET", "/v2/pets"));
    assertThat(d.modified()).isEmpty();
    assertThat(d.hasBreaking()).isTrue();
  }

  @Test
  void sameOperationIdWithNewMethod_isRemovedAndAdded() {
    var d = diff(model(op("listPets", "GET", "/pets")), model(op("listPets", "POST", "/pets")));
    assertThat(d.removed()).containsExactly(new OpRef("listPets", "GET", "/pets"));
    assertThat(d.added()).containsExactly(new OpRef("listPets", "POST", "/pets"));
    assertThat(d.modified()).isEmpty();
    assertThat(d.hasBreaking()).isTrue();
  }

  @Test
  void movedOperations_followEachModelsOrder() {
    var before = model(listPets, getPet);
    var after = model(op("getPet", "POST", "/pets/{id}"), op("listPets", "POST", "/pets"));
    var d = diff(before, after);
    assertThat(d.removed())
        .containsExactly(
            new OpRef("listPets", "GET", "/pets"), new OpRef("getPet", "GET", "/pets/{id}"));
    assertThat(d.added())
        .containsExactly(
            new OpRef("getPet", "POST", "/pets/{id}"), new OpRef("listPets", "POST", "/pets"));
    assertThat(d.modified()).isEmpty();
  }

  @Test
  void pathParamRenameOnly_isModifiedNotMoved() {
    var before = model(op("getPet", "GET", "/pets/{id}").withParam(pathParam("id")));
    var after = model(op("getPet", "GET", "/pets/{petId}").withParam(pathParam("petId")));
    var d = diff(before, after);
    assertThat(d.removed()).isEmpty();
    assertThat(d.added()).isEmpty();
    assertThat(d.modified()).hasSize(1);
    assertThat(d.modified().get(0).changes())
        .containsExactly(
            new Change(PARAM_ADDED, "path.petId", true),
            new Change(PARAM_REMOVED, "path.id", true));
  }

  @Test
  void newRequiredParam_isBreaking() {
    assertThat(onlyChange(listPets, listPets.withParam(query("limit", true))))
        .isEqualTo(new Change(PARAM_ADDED, "query.limit", true));
  }

  @Test
  void newOptionalParam_notBreaking() {
    assertThat(onlyChange(listPets, listPets.withParam(query("limit", false))).breaking())
        .isFalse();
  }

  @Test
  void optionalBecomesRequired_isBreaking() {
    assertThat(
            onlyChange(
                listPets.withParam(query("limit", false)),
                listPets.withParam(query("limit", true))))
        .isEqualTo(new Change(PARAM_BECAME_REQUIRED, "query.limit", true));
  }

  @Test
  void requiredBecomesOptional_notBreaking() {
    assertThat(
            onlyChange(
                listPets.withParam(query("limit", true)),
                listPets.withParam(query("limit", false))))
        .isEqualTo(new Change(PARAM_BECAME_OPTIONAL, "query.limit", false));
  }

  @Test
  void paramRemoved_isBreaking() {
    assertThat(onlyChange(listPets.withParam(query("limit", false)), listPets))
        .isEqualTo(new Change(PARAM_REMOVED, "query.limit", true));
  }

  @Test
  void bodyBecomesRequired_isBreaking() {
    assertThat(onlyChange(listPets.withBody(jsonBody(false)), listPets.withBody(jsonBody(true))))
        .isEqualTo(new Change(BODY_BECAME_REQUIRED, "body", true));
    assertThat(onlyChange(listPets, listPets.withBody(jsonBody(true))))
        .isEqualTo(new Change(BODY_BECAME_REQUIRED, "body", true));
  }

  @Test
  void optionalBodyAddedOrRemoved_isNotAChange() {
    assertThat(diff(model(listPets), model(listPets.withBody(jsonBody(false)))).isEmpty()).isTrue();
    assertThat(diff(model(listPets.withBody(jsonBody(false))), model(listPets)).isEmpty()).isTrue();
  }

  @Test
  void descriptionChanged_notBreaking() {
    assertThat(onlyChange(listPets.withSummary("List"), listPets.withSummary("List pets")))
        .isEqualTo(new Change(DESCRIPTION_CHANGED, "summary", false));
    assertThat(onlyChange(listPets.withDescription("a"), listPets.withDescription("b")))
        .isEqualTo(new Change(DESCRIPTION_CHANGED, "description", false));
    assertThat(
            onlyChange(
                listPets.withParam(query("limit", false, "old")),
                listPets.withParam(query("limit", false, "new"))))
        .isEqualTo(new Change(DESCRIPTION_CHANGED, "query.limit", false));
  }

  @Test
  void nullBefore_allAdded() {
    assertThat(differ.diff(null, model(listPets, getPet)).added()).hasSize(2);
  }

  @Test
  void identicalModels_emptyDiff() {
    var m = model(listPets, getPet);
    assertThat(diff(m, m).isEmpty()).isTrue();
  }

  /** One delegated schema comparison, with which sides were absent. */
  private record Call(String location, boolean beforeNull, boolean afterNull) {}

  @Test
  void schemaDiffer_isCalledForParamBodyAndResponses_withNullForMissingSide() {
    var calls = new ArrayList<Call>();
    SchemaDiffer recording =
        new SchemaDiffer() {
          @Override
          public List<Change> diffRequest(
              ApiModel before,
              Schema beforeSchema,
              ApiModel after,
              Schema afterSchema,
              String location) {
            calls.add(new Call(location, beforeSchema == null, afterSchema == null));
            return List.of();
          }

          @Override
          public List<Change> diffResponse(
              ApiModel before,
              Schema beforeSchema,
              ApiModel after,
              Schema afterSchema,
              String location) {
            calls.add(new Call(location, beforeSchema == null, afterSchema == null));
            return List.of();
          }
        };
    var before =
        listPets
            .withParam(query("limit", false))
            .withBody(jsonBody(false))
            .withResponse("200", string())
            .withResponse("404", string())
            .withResponse("204", null);
    var after =
        listPets
            .withParam(query("limit", false))
            .withBody(jsonBody(false))
            .withResponse("200", string())
            .withResponse("500", string())
            .withResponse("204", null);
    new SpecDiffer(recording).diff(model(before), model(after));
    // After-only statuses come in after order, then before-only statuses; a status with no
    // schema on either side is not delegated at all.
    assertThat(calls)
        .containsExactly(
            new Call("query.limit", false, false),
            new Call("body", false, false),
            new Call("200", false, false),
            new Call("500", true, false),
            new Call("404", false, true));
  }

  @Test
  void specDiffJson_hasExactTopLevelAndChangeFields() {
    var d = diff(model(listPets), model(listPets.withParam(query("limit", true))));
    JsonNode json = JsonMapper.builder().build().valueToTree(d);
    assertThat(json.propertyNames()).containsExactlyInAnyOrder("added", "removed", "modified");
    JsonNode change = json.get("modified").get(0).get("changes").get(0);
    assertThat(change.propertyNames()).containsExactlyInAnyOrder("kind", "location", "breaking");
  }
}
