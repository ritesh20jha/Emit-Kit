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
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** The generator for each {@link GeneratorId}. Every id has exactly one generator. */
@Component
public final class GeneratorRegistry {

  private final Map<GeneratorId, Generator> byId;

  /** Registers the generators shipped with this worker. */
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

  /** Registers the given generators. Fails unless there is exactly one per {@link GeneratorId}. */
  GeneratorRegistry(List<Generator> generators) {
    Map<GeneratorId, Generator> map = new EnumMap<>(GeneratorId.class);
    for (Generator generator : generators) {
      if (map.put(generator.id(), generator) != null) {
        throw new IllegalStateException("two generators for " + generator.id());
      }
    }
    List<GeneratorId> missing =
        Arrays.stream(GeneratorId.values()).filter(id -> !map.containsKey(id)).toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException("no generator for " + missing);
    }
    this.byId = Collections.unmodifiableMap(map);
  }

  /** The generator registered for {@code id}. */
  public Generator get(GeneratorId id) {
    return byId.get(id);
  }
}
