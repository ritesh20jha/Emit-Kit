package dev.emitkit.spec.version;

import java.util.Objects;
import java.util.Optional;

/** The version to record next. {@code changeType} is empty when the version is unchanged. */
public record VersionResult(String version, Optional<ChangeType> changeType) {

  public VersionResult {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(changeType, "changeType");
  }
}
