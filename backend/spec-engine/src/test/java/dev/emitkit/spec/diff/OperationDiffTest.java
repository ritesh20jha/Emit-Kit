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
import static dev.emitkit.spec.diff.ModelBuilder.query;
import static dev.emitkit.spec.diff.ModelBuilder.string;
import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.spec.diff.ModelBuilder.Op;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Schema;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

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
  void sameOperationIdWithNewPath_isNotAChange() {
    var d = diff(model(op("listPets", "GET", "/pets")), model(op("listPets", "GET", "/v2/pets")));
    assertThat(d.isEmpty()).isTrue();
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

  @Test
  void schemaDiffer_isCalledForSharedParamBodyAndResponse() {
    var locations = new ArrayList<String>();
    SchemaDiffer recording =
        new SchemaDiffer() {
          @Override
          public List<Change> diffRequest(
              ApiModel before,
              Schema beforeSchema,
              ApiModel after,
              Schema afterSchema,
              String location) {
            locations.add(location);
            return List.of();
          }

          @Override
          public List<Change> diffResponse(
              ApiModel before,
              Schema beforeSchema,
              ApiModel after,
              Schema afterSchema,
              String location) {
            locations.add(location);
            return List.of();
          }
        };
    Op op =
        listPets
            .withParam(query("limit", false))
            .withBody(jsonBody(false))
            .withResponse("200", string());
    new SpecDiffer(recording).diff(model(op), model(op));
    assertThat(locations).containsExactly("query.limit", "body", "200");
  }
}
