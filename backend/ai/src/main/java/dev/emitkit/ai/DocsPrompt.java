package dev.emitkit.ai;

import dev.emitkit.spec.model.AllOfSchema;
import dev.emitkit.spec.model.ArraySchema;
import dev.emitkit.spec.model.EnumSchema;
import dev.emitkit.spec.model.ObjectSchema;
import dev.emitkit.spec.model.OneOfSchema;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Parameter;
import dev.emitkit.spec.model.PrimitiveSchema;
import dev.emitkit.spec.model.RefSchema;
import dev.emitkit.spec.model.Response;
import dev.emitkit.spec.model.Schema;
import dev.emitkit.spec.model.Tag;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;

/**
 * Builds the prompt for one tag. The prompt names each operation's method, path, parameters and
 * schema names. It never includes spec descriptions or schema bodies.
 */
final class DocsPrompt {

  private static final PromptTemplate TEMPLATE =
      new PromptTemplate(new ClassPathResource("prompts/docs-examples.st"));

  private DocsPrompt() {}

  static String render(Tag tag, List<Operation> ops) {
    String operations = ops.stream().map(DocsPrompt::describe).collect(Collectors.joining("\n"));
    return TEMPLATE.render(Map.of("tag", tag.name(), "operations", operations));
  }

  private static String describe(Operation op) {
    var sb = new StringBuilder();
    sb.append("- operationId: ").append(op.operationId()).append('\n');
    sb.append("  ").append(op.method()).append(' ').append(op.path()).append('\n');
    for (Parameter p : op.parameters()) {
      sb.append("  parameter: ")
          .append(p.name())
          .append(" (")
          .append(p.in().name().toLowerCase(Locale.ROOT))
          .append(p.required() ? ", required" : "")
          .append(")\n");
    }
    if (op.requestBody() != null) {
      sb.append("  request body schema: ")
          .append(schemaName(op.requestBody().schema()))
          .append('\n');
    }
    for (Response r : op.responses().values()) {
      if (r.schema() != null) {
        sb.append("  response ")
            .append(r.status())
            .append(" schema: ")
            .append(schemaName(r.schema()))
            .append('\n');
      }
    }
    return sb.toString();
  }

  private static String schemaName(Schema schema) {
    return switch (schema) {
      case RefSchema ref -> ref.name();
      case ArraySchema array -> "array of " + schemaName(array.items());
      case PrimitiveSchema primitive -> primitive.type().name().toLowerCase(Locale.ROOT);
      case EnumSchema ignored -> "enum";
      case OneOfSchema ignored -> "oneOf";
      case AllOfSchema ignored -> "allOf";
      case ObjectSchema ignored -> "inline object";
    };
  }
}
