package dev.emitkit.spec.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiModelTest {

  private static Schema str() {
    return new PrimitiveSchema(PrimitiveType.STRING, null, false, null);
  }

  @Test
  void collectionsAreImmutableAndOrdered() {
    var props = new LinkedHashMap<String, Schema>();
    props.put("b", str());
    props.put("a", str());
    var o = new ObjectSchema(props, Set.of("a"), null, false, null);
    props.put("c", str());
    assertThat(o.properties().keySet()).containsExactly("b", "a");
    assertThatThrownBy(() -> o.properties().put("x", str()))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void schemaIsSealed() {
    assertThat(Schema.class.getPermittedSubclasses()).hasSize(7);
  }

  private static Operation op(String id) {
    return new Operation(
        id, false, "GET", "/" + id, "pets", null, null, List.of(), null, Map.of(), List.of(),
        false);
  }

  @Test
  void schemasAndOperationsKeepOrderAndAreUnmodifiable() {
    var schemas = new LinkedHashMap<String, Schema>();
    schemas.put("zeta", str());
    schemas.put("alpha", str());
    var operations = new ArrayList<>(List.of(op("second"), op("first")));
    var model =
        new ApiModel(
            new Info("Pets", "1", null, null), List.of(), Map.of(), List.of(), operations, schemas);
    schemas.put("mid", str());
    operations.add(op("third"));
    assertThat(model.schemas().keySet()).containsExactly("zeta", "alpha");
    assertThat(model.operations())
        .extracting(Operation::operationId)
        .containsExactly("second", "first");
    assertThatThrownBy(() -> model.schemas().put("x", str()))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> model.operations().add(op("x")))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullNonNullableComponentIsRejected() {
    assertThatThrownBy(
            () -> new ApiModel(null, List.of(), Map.of(), List.of(), List.of(), Map.of()))
        .isInstanceOf(NullPointerException.class);
  }
}
