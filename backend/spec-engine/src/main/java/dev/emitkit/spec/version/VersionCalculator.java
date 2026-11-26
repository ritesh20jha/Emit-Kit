package dev.emitkit.spec.version;

import com.github.zafarkhaja.semver.Version;
import dev.emitkit.spec.diff.SpecDiff;
import java.util.Objects;
import java.util.Optional;

/**
 * Computes the next spec version. Stateless; safe to share.
 *
 * <p>An empty or unparseable {@code lastVersion} is a first run under both strategies, so the
 * result is {@code 0.1.0} for {@link VersionStrategy#EMITKIT_MANAGED} and the {@code info.version}
 * (or {@code 0.1.0}) for {@link VersionStrategy#SPEC_VERSION}, both reported as {@code MINOR}.
 */
public final class VersionCalculator {

  private static final Version FIRST_VERSION = Version.of(0, 1, 0);

  public VersionResult next(
      VersionStrategy strategy, Optional<String> lastVersion, SpecDiff diff, String infoVersion) {
    Objects.requireNonNull(strategy, "strategy");
    Objects.requireNonNull(lastVersion, "lastVersion");
    Objects.requireNonNull(diff, "diff");
    Objects.requireNonNull(infoVersion, "infoVersion");
    Optional<Version> last = lastVersion.flatMap(Version::tryParse);
    return switch (strategy) {
      case EMITKIT_MANAGED -> emitkitManaged(last, diff);
      case SPEC_VERSION -> specVersion(last, infoVersion);
    };
  }

  private static VersionResult emitkitManaged(Optional<Version> last, SpecDiff diff) {
    if (last.isEmpty()) {
      return new VersionResult(FIRST_VERSION.toString(), Optional.of(ChangeType.MINOR));
    }
    Version current = last.get();
    if (diff.isEmpty()) {
      return new VersionResult(current.toString(), Optional.empty());
    }
    // Below 1.0.0 a breaking change bumps minor and additions bump patch.
    boolean pre1 = current.majorVersion() == 0;
    ChangeType applied;
    if (diff.hasBreaking()) {
      applied = pre1 ? ChangeType.MINOR : ChangeType.MAJOR;
    } else {
      applied = pre1 ? ChangeType.PATCH : ChangeType.MINOR;
    }
    return new VersionResult(bump(current, applied).toString(), Optional.of(applied));
  }

  /**
   * The version is {@code info.version}, or {@code 0.1.0} when it is not valid semver. A difference
   * from the last version is labelled by its highest differing component, whichever direction it
   * moves in. So a downgrade (2.0.0 to 1.5.0) is labelled MAJOR, and an invalid {@code
   * info.version} that falls back to {@code 0.1.0} is labelled by the same rule. Callers must guard
   * against reusing an existing version.
   */
  private static VersionResult specVersion(Optional<Version> last, String infoVersion) {
    Optional<Version> info = Version.tryParse(infoVersion);
    Version resulting = info.orElse(FIRST_VERSION);
    String version = info.isPresent() ? infoVersion : FIRST_VERSION.toString();
    if (last.isEmpty()) {
      return new VersionResult(version, Optional.of(ChangeType.MINOR));
    }
    return new VersionResult(version, changeBetween(last.get(), resulting));
  }

  private static Version bump(Version version, ChangeType type) {
    return switch (type) {
      case MAJOR -> Version.of(version.majorVersion() + 1, 0, 0);
      case MINOR -> Version.of(version.majorVersion(), version.minorVersion() + 1, 0);
      case PATCH ->
          Version.of(version.majorVersion(), version.minorVersion(), version.patchVersion() + 1);
    };
  }

  /** Highest differing component; a pre-release or build-metadata difference is a PATCH. */
  private static Optional<ChangeType> changeBetween(Version from, Version to) {
    if (from.majorVersion() != to.majorVersion()) {
      return Optional.of(ChangeType.MAJOR);
    }
    if (from.minorVersion() != to.minorVersion()) {
      return Optional.of(ChangeType.MINOR);
    }
    if (from.patchVersion() != to.patchVersion()) {
      return Optional.of(ChangeType.PATCH);
    }
    return from.toString().equals(to.toString()) ? Optional.empty() : Optional.of(ChangeType.PATCH);
  }
}
