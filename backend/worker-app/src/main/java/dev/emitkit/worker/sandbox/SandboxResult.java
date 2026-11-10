package dev.emitkit.worker.sandbox;

import java.nio.file.Path;

/**
 * Outputs of one sandbox run. {@code outputTar} is a temp file the caller must delete. A null
 * output is absent, rejected by a limit, or not copied back. A null {@code reportJson} with {@code
 * timedOut=false} means the check failed. {@code timedOut} means the container was killed.
 */
public record SandboxResult(Path outputTar, String reportJson, boolean timedOut) {}
