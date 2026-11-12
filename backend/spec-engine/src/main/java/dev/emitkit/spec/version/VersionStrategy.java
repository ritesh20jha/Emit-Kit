package dev.emitkit.spec.version;

/** How a spec's version number is chosen. Names match the domain enum; the domain maps by name. */
public enum VersionStrategy {
  /** Bumped from the operation diff, starting at {@code 0.1.0}. */
  EMITKIT_MANAGED,
  /** Taken from {@code info.version} when it is valid SemVer. */
  SPEC_VERSION
}
