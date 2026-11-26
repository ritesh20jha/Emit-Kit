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
    requireRepoRelative(path);
  }

  private static void requireRepoRelative(String path) {
    if (path.isBlank()) {
      throw new IllegalArgumentException("path must not be blank: \"" + path + "\"");
    }
    if (path.startsWith("/")) {
      throw new IllegalArgumentException("path must be relative: \"" + path + "\"");
    }
    if (path.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("path must use '/' separators: \"" + path + "\"");
    }
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException(
            "path has an empty, '.' or '..' segment: \"" + path + "\"");
      }
    }
  }
}
