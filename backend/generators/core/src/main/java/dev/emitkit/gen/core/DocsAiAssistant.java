package dev.emitkit.gen.core;

import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Tag;
import java.util.List;
import java.util.Map;

/**
 * Supplies AI-written examples for the docs generator. Implemented in the {@code ai} module so that
 * generators stay free of network I/O.
 */
public interface DocsAiAssistant {

  /**
   * Returns examples keyed by {@code operationId}. Operations missing from the map fall back to the
   * template text.
   */
  Map<String, DocsExamples> examples(Tag tag, List<Operation> ops);
}
