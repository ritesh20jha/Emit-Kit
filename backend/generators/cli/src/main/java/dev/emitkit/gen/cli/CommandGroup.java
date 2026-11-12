package dev.emitkit.gen.cli;

import static dev.emitkit.gen.cli.TsLiterals.oneLine;
import static dev.emitkit.gen.cli.TsLiterals.quote;

import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A command group, rendered as {@code src/commands/{name}.ts}. Each operation carries only its
 * first tag, as the model records it, so a tag appears only when an operation uses it first.
 */
record CommandGroup(
    int index,
    String name,
    String nameLiteral,
    String descriptionLiteral,
    String importPathLiteral,
    List<CommandOperation> operations) {

  /** Tag the parser assigns to operations that declare no tag. */
  static final String UNTAGGED = "default";

  /** One group per distinct operation tag, in first-appearance order. */
  static List<CommandGroup> of(ApiModel model) {
    Map<String, List<Operation>> byTag = new LinkedHashMap<>();
    for (Operation op : model.operations()) {
      byTag.computeIfAbsent(op.tag(), tag -> new ArrayList<>()).add(op);
    }
    List<String> tags = List.copyOf(byTag.keySet());
    List<String> names =
        Names.unique(tags.stream().map(Names::kebab).toList(), Names.RESERVED_COMMANDS);
    List<CommandGroup> groups = new ArrayList<>();
    for (int i = 0; i < tags.size(); i++) {
      String tag = tags.get(i);
      String name = names.get(i);
      groups.add(
          new CommandGroup(
              i,
              name,
              quote(name),
              quote(description(tag, model)),
              quote("./commands/" + name + ".js"),
              CommandOperation.of(byTag.get(tag))));
    }
    return List.copyOf(groups);
  }

  private static String description(String tag, ApiModel model) {
    for (Tag declared : model.tags()) {
      if (declared.name().equals(tag) && declared.description() != null) {
        String text = oneLine(declared.description());
        if (!text.isEmpty()) {
          return text;
        }
      }
    }
    boolean untagged =
        tag.equals(UNTAGGED) && model.tags().stream().noneMatch(t -> t.name().equals(tag));
    return untagged ? "Operations without a tag" : "Operations tagged " + oneLine(tag);
  }
}
