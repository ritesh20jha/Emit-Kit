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
   * Opens the object stored under {@code key}. The caller must close the returned stream. Closing
   * before the end may drain the rest of the response body, so read it fully when possible.
   *
   * @throws NoSuchArtifactException if no object exists under {@code key}
   */
  InputStream get(String key);

  /** Removes the object under {@code key}. A missing key is a no-op. */
  void delete(String key);

  /**
   * Returns the keys under {@code prefix} whose last modification is strictly before {@code
   * cutoff}. Every matching key is loaded into memory, and the listing makes one request per 1000
   * keys scanned, so keep prefixes narrow.
   */
  List<String> listOlderThan(String prefix, Instant cutoff);
}
