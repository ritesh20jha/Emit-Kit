package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.StepName;
import java.util.Optional;
import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The step outputs of a run: a JSON object from step name to the output that step saved. */
public final class StepOutputs {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private StepOutputs() {}

  /**
   * Returns {@code json} with {@code output} saved under {@code step}, replacing any earlier one.
   */
  public static String with(String json, StepName step, @Nullable Object output) {
    ObjectNode root = parse(json);
    if (output == null) {
      root.putNull(step.name());
    } else {
      root.set(step.name(), JSON.valueToTree(output));
    }
    return JSON.writeValueAsString(root);
  }

  /** Returns {@code json} without the outputs of {@code steps}. */
  static String without(String json, StepName... steps) {
    ObjectNode root = parse(json);
    for (StepName step : steps) {
      root.remove(step.name());
    }
    return JSON.writeValueAsString(root);
  }

  /** Whether {@code step} saved an output, even a null one. */
  static boolean has(String json, StepName step) {
    return parse(json).has(step.name());
  }

  /** The output saved under {@code step}, deserialized as {@code type}. Empty if none was saved. */
  public static <T> Optional<T> read(String json, StepName step, Class<T> type) {
    JsonNode node = parse(json).get(step.name());
    return node == null ? Optional.empty() : Optional.ofNullable(JSON.treeToValue(node, type));
  }

  private static ObjectNode parse(String json) {
    return (ObjectNode) JSON.readTree(json);
  }
}
