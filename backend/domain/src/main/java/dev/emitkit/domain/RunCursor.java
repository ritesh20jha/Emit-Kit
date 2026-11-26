package dev.emitkit.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;

/**
 * Position in the run list, newest first. The token is the URL-safe base64 of {@code
 * {createdAt}|{id}} and names the row after the last one returned. Clients treat it as opaque.
 *
 * @param createdAt the creation time of the last run on the previous page
 * @param id the id of that run, which breaks ties between runs created at the same instant
 */
public record RunCursor(Instant createdAt, String id) {

  private static final char SEPARATOR = '|';

  /** Returns the token for the position just after a run created at {@code createdAt}. */
  public static String encode(Instant createdAt, String id) {
    String raw = createdAt + String.valueOf(SEPARATOR) + id;
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Reads a token made by {@link #encode}.
   *
   * @throws IllegalArgumentException if the token is not a valid cursor
   */
  public static RunCursor decode(String token) {
    try {
      String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
      int separator = raw.indexOf(SEPARATOR);
      if (separator < 0) {
        throw new IllegalArgumentException("Malformed run cursor");
      }
      return new RunCursor(
          Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
    } catch (IllegalArgumentException | DateTimeParseException e) {
      throw new IllegalArgumentException("Malformed run cursor", e);
    }
  }
}
