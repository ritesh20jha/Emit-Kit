package dev.emitkit.common.id;

import com.github.f4b6a3.ulid.UlidCreator;

/** Factory for identifiers used across EmitKit services. */
public final class Ids {

  private Ids() {}

  /**
   * Returns a new ULID: 26 Crockford base32 characters, lexicographically sortable by creation
   * time. Values produced within the same millisecond stay strictly increasing (monotonic).
   *
   * @return a new ULID string
   */
  public static String newUlid() {
    return UlidCreator.getMonotonicUlid().toString();
  }
}
