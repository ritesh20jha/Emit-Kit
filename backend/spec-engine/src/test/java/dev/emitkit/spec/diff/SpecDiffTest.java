package dev.emitkit.spec.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SpecDiffTest {

  private static final OpRef LIST_PETS = new OpRef("listPets", "get", "/pets");
  private static final OpRef DELETE_PET = new OpRef("deletePet", "delete", "/pets/{id}");

  @Test
  void emptyDiffHasNoChangesAndNoBreaking() {
    assertThat(SpecDiff.empty().isEmpty()).isTrue();
    assertThat(SpecDiff.empty().hasBreaking()).isFalse();
  }

  @Test
  void additionsAloneAreNotBreaking() {
    SpecDiff diff = new SpecDiff(List.of(LIST_PETS), List.of(), List.of());

    assertThat(diff.isEmpty()).isFalse();
    assertThat(diff.hasBreaking()).isFalse();
  }

  @Test
  void removedOperationIsBreaking() {
    SpecDiff diff = new SpecDiff(List.of(), List.of(DELETE_PET), List.of());

    assertThat(diff.isEmpty()).isFalse();
    assertThat(diff.hasBreaking()).isTrue();
  }

  @Test
  void breakingChangeInModifiedOperationIsBreaking() {
    OpChange change =
        new OpChange(
            LIST_PETS, List.of(new Change(ChangeKind.PARAM_BECAME_REQUIRED, "query.limit", true)));

    assertThat(new SpecDiff(List.of(), List.of(), List.of(change)).hasBreaking()).isTrue();
  }

  @Test
  void nonBreakingChangeInModifiedOperationIsNotBreaking() {
    OpChange change =
        new OpChange(
            LIST_PETS,
            List.of(new Change(ChangeKind.RESPONSE_PROPERTY_ADDED, "200.items[].id", false)));

    assertThat(new SpecDiff(List.of(), List.of(), List.of(change)).hasBreaking()).isFalse();
  }

  /** The stored and served JSON is exactly added, removed and modified, nothing derived. */
  @Test
  void jsonHasOnlyTheThreeLists() {
    JsonNode json =
        JsonMapper.builder()
            .build()
            .valueToTree(new SpecDiff(List.of(LIST_PETS), List.of(), List.of()));

    assertThat(json.propertyNames()).containsExactlyInAnyOrder("added", "removed", "modified");
  }
}
