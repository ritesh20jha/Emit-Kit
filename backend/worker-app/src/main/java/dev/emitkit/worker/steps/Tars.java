package dev.emitkit.worker.steps;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.FileSystemUtils;

/** Writes the sandbox's input tar and reads its output tar, in the SBX-02 layout. */
final class Tars {

  /** The entry that lists the languages to check, one per line. */
  static final String LANGUAGES = "languages.txt";

  /** An output file may grow to this many bytes, or {@link #ENTRY_GROWTH} times its size. */
  static final long MIN_ENTRY_LIMIT = 1L << 20;

  static final long ENTRY_GROWTH = 4;

  /** The formatted output may hold at most this many bytes in all. */
  static final long TOTAL_LIMIT = 64L << 20;

  private static final String SRC = "src/";
  private static final int BUFFER = 64 * 1024;
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
   * Reads the {@code src/<path>} regular files of {@code outTar} that are in {@code allowed} into
   * {@code stage}, one file each, and returns the staged file for each path. Names outside the set
   * (new files, directories, links, names with {@code ..}) are skipped, and a duplicate replaces
   * the earlier copy. The whole archive is read before this returns, so nothing is installed from a
   * bad archive.
   *
   * @throws RejectedOutputException if the archive is unreadable or over a limit
   */
  static Map<String, Path> stage(Path outTar, Path stage, Path filesDir, Set<String> allowed)
      throws IOException {
    Map<String, Path> staged = new LinkedHashMap<>();
    long total = 0;
    int copies = 0;
    try (TarArchiveInputStream tar = new TarArchiveInputStream(Files.newInputStream(outTar))) {
      for (TarArchiveEntry entry = next(tar); entry != null; entry = next(tar)) {
        String name = entry.getName();
        if (!isRegularFile(entry) || !name.startsWith(SRC)) {
          continue;
        }
        String path = name.substring(SRC.length());
        if (!allowed.contains(path)) {
          continue;
        }
        long limit = Math.max(MIN_ENTRY_LIMIT, ENTRY_GROWTH * Files.size(filesDir.resolve(path)));
        Path file = stage.resolve(Integer.toString(copies++));
        total += copy(tar, file, path, limit);
        if (total > TOTAL_LIMIT) {
          throw new RejectedOutputException("sandbox output is over the total size limit");
        }
        staged.put(path, file);
      }
    }
    return staged;
  }

  /** Moves each staged file over its work-dir file. Each move is atomic on the same filesystem. */
  static void install(Map<String, Path> staged, Path filesDir) throws IOException {
    for (Map.Entry<String, Path> file : staged.entrySet()) {
      Files.move(file.getValue(), filesDir.resolve(file.getKey()), StandardCopyOption.ATOMIC_MOVE);
    }
  }

  /** Deletes a file or a directory tree, logging a failure. Null is ignored. */
  static void deleteQuietly(Path file) {
    if (file == null) {
      return;
    }
    try {
      FileSystemUtils.deleteRecursively(file);
    } catch (IOException e) {
      LOG.warn("could not delete temp file {}", file, e);
    }
  }

  /**
   * The sandbox's output is untrusted. This is thrown when it cannot be read or is over a limit, so
   * the step fails the check rather than retrying it.
   */
  static final class RejectedOutputException extends IOException {

    RejectedOutputException(String message) {
      super(message);
    }

    RejectedOutputException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The next entry. A read error from the archive means the output is unreadable. */
  private static TarArchiveEntry next(TarArchiveInputStream tar) throws RejectedOutputException {
    try {
      return tar.getNextEntry();
    } catch (IOException e) {
      throw unreadable(e);
    }
  }

  /**
   * Copies the current entry to {@code target}, failing if it grows past {@code limit}. A read from
   * the archive that fails is an unreadable output; a failed write is a local error.
   */
  private static long copy(TarArchiveInputStream tar, Path target, String path, long limit)
      throws IOException {
    long total = 0;
    byte[] buffer = new byte[BUFFER];
    try (OutputStream out = Files.newOutputStream(target)) {
      for (int n = read(tar, buffer); n != -1; n = read(tar, buffer)) {
        total += n;
        if (total > limit) {
          throw new RejectedOutputException(
              "sandbox output file " + path + " is over its size limit");
        }
        out.write(buffer, 0, n);
      }
    }
    return total;
  }

  private static int read(InputStream tar, byte[] buffer) throws RejectedOutputException {
    try {
      return tar.read(buffer);
    } catch (IOException e) {
      throw unreadable(e);
    }
  }

  private static RejectedOutputException unreadable(IOException cause) {
    return new RejectedOutputException("sandbox output is unreadable", cause);
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
