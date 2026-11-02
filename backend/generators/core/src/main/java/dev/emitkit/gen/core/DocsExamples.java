package dev.emitkit.gen.core;

import java.util.Objects;

/** Per-operation code examples supplied by an AI assistant for the docs generator. */
public record DocsExamples(
    String curl, String typescript, String python, String java, String notes) {

  public DocsExamples {
    Objects.requireNonNull(curl, "curl");
    Objects.requireNonNull(typescript, "typescript");
    Objects.requireNonNull(python, "python");
    Objects.requireNonNull(java, "java");
    Objects.requireNonNull(notes, "notes");
  }
}
