package dev.emitkit.worker.steps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Writes the sandbox's input tar and reads its output tar, in the SBX-02 layout. */
final class Tars {

  /** The entry that lists the languages to check, one per line. */
  static final String LANGUAGES = "languages.txt";

  private static final String SRC = "src/";
  private static final Logger LOG = LoggerFactory.getLogger(Tars.class);

  private Tars() {}

  /** Writes {@code src/<path>} for each path under {@code filesDir}, then {@code languages.txt}. */
  static void writeInput(Path target, Path filesDir, List<String> paths, Set<String> languages)
      throws IOException {
    try (TarArchiveOutputStream tar = new TarArchiveOutputStream(Files.newOutputStream(target))) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (String path : paths) {
        add(tar, SRC + path, Files.readAllBytes(filesDir.resolve(path)));
      }
      add(tar, LANGUAGES, (String.join("\n", languages) + "\n").getBytes(StandardCharsets.UTF_8));
    }
  }

  /**
   * Copies each {@code src/<path>} regular file of {@code outTar} over the file at {@code path}
   * under {@code filesDir}, for each {@code path} in {@code allowed} only. Anything else (new
   * names, directories, links, names with {@code ..}) is skipped. Returns the number replaced.
   */
  static int replaceFiles(Path outTar, Path filesDir, Set<String> allowed) throws IOException {
    int replaced = 0;
    try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(outTar))) {
      for (TarArchiveEntry entry = tar.getNextEntry(); entry != null; entry = tar.getNextEntry()) {
        String name = entry.getName();
        if (!isRegularFile(entry) || !name.startsWith(SRC)) {
          continue;
        }
        String path = name.substring(SRC.length());
        if (allowed.contains(path)) {
          Files.copy(tar, filesDir.resolve(path), StandardCopyOption.REPLACE_EXISTING);
          replaced++;
        }
      }
    }
    return replaced;
  }

  /** Deletes a temp file, logging a failure. Null is ignored. */
  static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LOG.warn("could not delete temp file {}", file, e);
    }
  }

  private static void add(TarArchiveOutputStream tar, String name, byte[] content)
      throws IOException {
    TarArchiveEntry entry = new TarArchiveEntry(name);
    entry.setSize(content.length);
    tar.putArchiveEntry(entry);
    tar.write(content);
    tar.closeArchiveEntry();
  }

  /** {@code isFile()} alone also accepts symlinks, so the link and device types are checked too. */
  private static boolean isRegularFile(TarArchiveEntry e) {
    return e.isFile()
        && !(e.isDirectory()
            || e.isSymbolicLink()
            || e.isLink()
            || e.isCharacterDevice()
            || e.isBlockDevice()
            || e.isFIFO());
  }
}
