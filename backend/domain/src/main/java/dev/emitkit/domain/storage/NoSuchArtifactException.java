package dev.emitkit.domain.storage;

/** Thrown when an artifact key has no stored object. */
public class NoSuchArtifactException extends RuntimeException {

  public NoSuchArtifactException(String key, Throwable cause) {
    super("No artifact stored under key: " + key, cause);
  }
}
