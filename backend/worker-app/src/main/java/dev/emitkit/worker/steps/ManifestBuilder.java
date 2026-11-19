package dev.emitkit.worker.steps;

import dev.emitkit.domain.FileStatus;
import dev.emitkit.domain.ManifestEntry;
import dev.emitkit.gen.core.FileKind;
import dev.emitkit.vcs.GitBlob;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * Builds the file manifest of a run's artifact by comparing each generated file with the same path
 * on the target branch (spec v1 §5.1 step 9). Pure: the caller reads the files and the target.
 */
final class ManifestBuilder {

  private ManifestBuilder() {}

  /**
   * One file of the artifact: its manifest entry and its content. The zip carries every file except
   * the {@link FileStatus#SKIPPED} ones.
   */
  record Planned(ManifestEntry entry, String content) {

    boolean zipped() {
      return entry.status() != FileStatus.SKIPPED;
    }
  }

  /**
   * Plans every generated file, sorted by path so that the manifest and the zip do not depend on
   * the order the generators wrote the files in.
   *
   * @param files the run's files, as the generators wrote them
   * @param contents the content of each file, by path
   * @param targetBlobs the git blob sha of each file on the target branch, by path
   */
  static List<Planned> plan(
      List<WorkDir.Entry> files, Map<String, String> contents, Map<String, String> targetBlobs) {
    return files.stream()
        .sorted(Comparator.comparing(WorkDir.Entry::path))
        .map(file -> planOne(file, contents.get(file.path()), targetBlobs.get(file.path())))
        .toList();
  }

  private static Planned planOne(WorkDir.Entry file, String content, @Nullable String targetSha) {
    ManifestEntry entry =
        new ManifestEntry(
            file.path(),
            sha256(content),
            content.getBytes(StandardCharsets.UTF_8).length,
            status(file, content, targetSha));
    return new Planned(entry, content);
  }

  /**
   * A new file is {@code ADDED}. A user-owned stub the target already has is {@code SKIPPED}. Any
   * other file is {@code UNCHANGED} when its content matches the target, else {@code MODIFIED}.
   */
  private static FileStatus status(WorkDir.Entry file, String content, @Nullable String targetSha) {
    if (targetSha == null) {
      return FileStatus.ADDED;
    }
    if (file.kind() == FileKind.CUSTOM_STUB) {
      return FileStatus.SKIPPED;
    }
    return targetSha.equals(GitBlob.sha(content)) ? FileStatus.UNCHANGED : FileStatus.MODIFIED;
  }

  /** The lowercase hex SHA-256 of the UTF-8 bytes of {@code content}. */
  static String sha256(String content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
