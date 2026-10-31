package dev.emitkit.domain.storage;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Stores run artifacts (zip packages) by key, e.g. {@code artifacts/{orgId}/{runId}.zip}. */
public interface ArtifactStorage {

  /** Uploads {@code file} under {@code key}, overwriting any existing object. */
  void put(String key, Path file);

  /**
   * Opens the object stored under {@code key}. The caller must close the returned stream.
   *
   * @throws NoSuchArtifactException if no object exists under {@code key}
   */
  InputStream get(String key);

  /** Removes the object under {@code key}. A missing key is a no-op. */
  void delete(String key);

  /**
   * Returns the keys under {@code prefix} whose last modification is strictly before {@code
   * cutoff}.
   */
  List<String> listOlderThan(String prefix, Instant cutoff);
}
