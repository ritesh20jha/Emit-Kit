package dev.emitkit.domain.storage;

/**
 * Storage backend failure: a server error (HTTP 5xx) or a transport I/O failure. Extends
 * RuntimeException until CORE-01 provides TransientException.
 */
public class StorageException extends RuntimeException {

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
