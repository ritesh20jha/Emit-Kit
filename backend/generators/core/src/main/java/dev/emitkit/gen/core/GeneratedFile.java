package dev.emitkit.gen.core;

import java.util.Objects;

/**
 * A file produced by a generator.
 *
 * @param path relative to the repository root, using {@code '/'} separators
 * @param content full file content
 * @param kind whether the file is generated or a custom stub
 */
public record GeneratedFile(String path, String content, FileKind kind) {

  public GeneratedFile {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(kind, "kind");
  }
}
