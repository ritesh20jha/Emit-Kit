package dev.emitkit.worker.steps;

import dev.emitkit.gen.cli.CliGenerator;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.docs.DocsGenerator;
import dev.emitkit.gen.javasdk.JavaSdkGenerator;
import dev.emitkit.gen.mcp.McpGenerator;
import dev.emitkit.gen.pythonsdk.PythonSdkGenerator;
import dev.emitkit.gen.typescriptsdk.TypeScriptSdkGenerator;
import dev.emitkit.gen.workflows.WorkflowsGenerator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The generator for each {@link GeneratorId}. */
@Component
public final class GeneratorRegistry {

  private final Map<GeneratorId, Generator> byId = new EnumMap<>(GeneratorId.class);

  public GeneratorRegistry() {
    this(
        List.of(
            new TypeScriptSdkGenerator(),
            new PythonSdkGenerator(),
            new JavaSdkGenerator(),
            new CliGenerator(),
            new McpGenerator(),
            new DocsGenerator(),
            new WorkflowsGenerator()));
  }

  public GeneratorRegistry(List<Generator> generators) {
    generators.forEach(generator -> byId.put(generator.id(), generator));
  }

  public Generator get(GeneratorId id) {
    return byId.get(id);
  }
}
