package dev.emitkit.spec.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
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
}
