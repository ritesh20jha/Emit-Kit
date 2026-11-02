package dev.emitkit.spec.parse;

import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.PrimitiveType;
import dev.emitkit.spec.model.Schema;
import org.jspecify.annotations.Nullable;

/** Maps swagger schemas to the model. Stub: SPEC-03 replaces this with the full mapping. */
final class SchemaMapper {

  private SchemaMapper() {}

  static Schema map(io.swagger.v3.oas.models.media.@Nullable Schema<?> schema) {
    return new PrimitiveSchema(PrimitiveType.STRING, null, false, null);
  }
}
