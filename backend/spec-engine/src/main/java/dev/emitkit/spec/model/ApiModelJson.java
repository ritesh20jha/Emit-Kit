package dev.emitkit.spec.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON form of an {@link ApiModel}, used for snapshot storage and diffing. Schema nodes are tagged
 * with a {@code kind} property ({@code object}, {@code array}, {@code primitive}, {@code enum},
 * {@code ref}, {@code oneOf}, {@code allOf}). The model records carry no Jackson annotations; the
 * tagging lives in a mixin applied to {@link Schema}.
 */
public final class ApiModelJson {

  private static final JsonMapper MAPPER =
      JsonMapper.builder()
          .addMixIn(Schema.class, SchemaMixin.class)
          // Sets deserialize to LinkedHashSet so the document order of "required" survives.
          .addModule(new SimpleModule().addAbstractTypeMapping(Set.class, LinkedHashSet.class))
          .build();

  private ApiModelJson() {}

  /**
   * Serializes a model to compact JSON.
   *
   * @param model the model; must not be null
   * @return the JSON document
   */
  public static String toJson(ApiModel model) {
    Objects.requireNonNull(model, "model");
    try {
      return MAPPER.writeValueAsString(model);
    } catch (JacksonException e) {
      throw new IllegalStateException("ApiModel could not be serialized", e);
    }
  }

  /**
   * Reads a model back from JSON produced by {@link #toJson(ApiModel)}.
   *
   * @param json the JSON document; must not be null
   * @return the model
   * @throws IllegalArgumentException if the document is malformed or violates the model's
   *     invariants
   */
  public static ApiModel fromJson(String json) {
    Objects.requireNonNull(json, "json");
    try {
      return MAPPER.readValue(json, ApiModel.class);
    } catch (JacksonException e) {
      throw new IllegalArgumentException("not a valid ApiModel document", e);
    }
  }

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
  @JsonSubTypes({
    @JsonSubTypes.Type(value = ObjectSchema.class, name = "object"),
    @JsonSubTypes.Type(value = ArraySchema.class, name = "array"),
    @JsonSubTypes.Type(value = PrimitiveSchema.class, name = "primitive"),
    @JsonSubTypes.Type(value = EnumSchema.class, name = "enum"),
    @JsonSubTypes.Type(value = RefSchema.class, name = "ref"),
    @JsonSubTypes.Type(value = OneOfSchema.class, name = "oneOf"),
    @JsonSubTypes.Type(value = AllOfSchema.class, name = "allOf"),
  })
  private interface SchemaMixin {}
}
