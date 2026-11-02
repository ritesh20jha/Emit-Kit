package dev.emitkit.gen.core;

import dev.emitkit.spec.model.ApiModel;
import java.util.List;

/** A pure function from an API model and context to the files it emits. Performs no I/O. */
public interface Generator {

  GeneratorId id();

  List<GeneratedFile> generate(ApiModel model, GeneratorContext ctx);
}
