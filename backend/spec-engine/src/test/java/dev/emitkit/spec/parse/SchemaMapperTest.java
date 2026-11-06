package dev.emitkit.spec.parse;

import static dev.emitkit.spec.model.PrimitiveType.INTEGER;
import static dev.emitkit.spec.model.PrimitiveType.STRING;
import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Schema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SchemaMapperTest {

  private static final SpecParser PARSER = new SpecParser();

  private static Schema map(String name) {
    return map("schemas.yaml", name);
  }

  private static Schema map(String file, String name) {
    var r = PARSER.parse(Fixtures.read(file));
    assertThat(r.ok()).as("errors: %s", r.errors()).isTrue();
    return r.model().schemas().get(name);
  }

  private static Schema prop(String component, String property) {
    return ((ObjectSchema) map(component)).properties().get(property);
  }

  @Test
  void primitiveWithFormat() {
    assertThat(map("DateField")).isEqualTo(new PrimitiveSchema(STRING, "date-time", false, null));
  }

  @Test
  void refKeptAsRef() {
    assertThat(prop("Owner", "pet")).isEqualTo(new RefSchema("Pet", false, null));
  }

  @Test
  void enumOfStrings() {
    assertThat(map("Status"))
        .isEqualTo(new EnumSchema(STRING, List.of("available", "sold"), false, null));
  }

  @Test
  void arrayOfRefs() {
    assertThat(map("PetList"))
        .isEqualTo(new ArraySchema(new RefSchema("Pet", false, null), false, null));
  }

  @Test
  void requiredSetKept() {
    assertThat(((ObjectSchema) map("Pet")).required()).containsExactly("id", "name");
  }

  @Test
  void oneOfWithDiscriminator() {
    var s = (OneOfSchema) map("Shape");
    assertThat(s.discriminator()).isEqualTo("kind");
    assertThat(s.options()).hasSize(2);
  }

  @Test
  void allOfParts() {
    assertThat(((AllOfSchema) map("Dog")).parts()).hasSize(2);
  }

  @Test
  void allOfSiblingsBecomeTrailingObjectPart() {
    var s = (AllOfSchema) map("Extended");
    assertThat(s.parts()).hasSize(2);
    assertThat(s.parts().get(0)).isEqualTo(new RefSchema("Pet", false, null));
    assertThat(s.parts().get(1))
        .isEqualTo(
            new ObjectSchema(
                Map.of("extra", new PrimitiveSchema(STRING, null, false, null)),
                Set.of("extra"),
                null,
                false,
                null));
  }

  @Test
  void nullable30() {
    // 3.1 drops the 3.0 keyword, so the 3.0 form needs its own document.
    assertThat(map("schemas-30.yaml", "Nullable30"))
        .isEqualTo(new PrimitiveSchema(STRING, null, true, null));
  }

  @Test
  void nullable31TypeArray() {
    assertThat(map("Nullable31")).isEqualTo(new PrimitiveSchema(STRING, null, true, null));
  }

  @Test
  void oneOfWithNullBranchBecomesNullable() {
    assertThat(map("MaybePet")).isEqualTo(new RefSchema("Pet", true, null));
  }

  @Test
  void additionalPropertiesMap() {
    assertThat(((ObjectSchema) map("Labels")).additionalProperties())
        .isInstanceOf(PrimitiveSchema.class);
  }

  @Test
  void recursiveSchemaTerminates() {
    assertThat(prop("Node", "children"))
        .isEqualTo(new ArraySchema(new RefSchema("Node", false, null), false, null));
  }

  @Test
  void anyOfWithNullBecomesNullable() {
    assertThat(map("MaybeInt")).isEqualTo(new PrimitiveSchema(INTEGER, null, true, null));
  }

  @Test
  void anyOfWithSeveralOptionsIsAUnion() {
    var s = (OneOfSchema) map("Either");
    assertThat(s.options())
        .containsExactly(
            new RefSchema("Pet", false, null), new PrimitiveSchema(INTEGER, null, false, null));
    assertThat(s.discriminator()).isNull();
    assertThat(s.nullable()).isFalse();
  }

  @Test
  void additionalPropertiesWithoutTypeIsAnObject() {
    assertThat(((ObjectSchema) map("Map31")).additionalProperties())
        .isEqualTo(new PrimitiveSchema(STRING, null, false, null));
  }

  @Test
  void freeFormObjectIsOpen() {
    assertThat(map("Free")).isEqualTo(new ObjectSchema(Map.of(), Set.of(), null, false, null));
  }

  @Test
  void singlePartAllOfCollapsesKeepingDescription() {
    assertThat(map("DescribedPet")).isEqualTo(new RefSchema("Pet", false, "A pet, described"));
  }

  @Test
  void singlePartAllOfCollapsesKeepingNullable() {
    assertThat(map("schemas-30.yaml", "NullableRef")).isEqualTo(new RefSchema("Pet", true, null));
  }

  @Test
  void untypedAndAbsentSchemasAreOpenObjects() {
    var open = new ObjectSchema(Map.of(), Set.of(), null, false, null);
    assertThat(map("Anything")).isEqualTo(open);
    assertThat(SchemaMapper.map(null)).isEqualTo(open);
  }
}
