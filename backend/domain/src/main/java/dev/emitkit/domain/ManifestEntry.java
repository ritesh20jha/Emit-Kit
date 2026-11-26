package dev.emitkit.domain;

/**
 * One file of a run's artifact. Stored inside {@code run_artifacts.manifest} (JSONB). {@code
 * sha256} is the hex digest of the file content and {@code size} is its length in bytes.
 */
public record ManifestEntry(String path, String sha256, long size, FileStatus status) {}
