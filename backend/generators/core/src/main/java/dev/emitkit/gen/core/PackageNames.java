package dev.emitkit.gen.core;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Package coordinates for the generated SDKs. Ecosystem-specific fields are {@code null} when that
 * ecosystem is not requested.
 */
public record PackageNames(
    @Nullable String npmScope,
    String npmName,
    @Nullable String pypiName,
    @Nullable String mavenGroupId,
    @Nullable String mavenArtifactId) {

  public PackageNames {
    Objects.requireNonNull(npmName, "npmName");
  }
}
