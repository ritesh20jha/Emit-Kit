package dev.emitkit.worker.sandbox;

import java.nio.file.Path;

/**
 * Outputs of one sandbox run.
 *
 * @param outputTar the {@code out.tar} the check wrote, in a temp file the caller must delete; null
 *     if none, if it was rejected by a size or name limit, or if the run timed out
 * @param reportJson the {@code report.json} text; null if none, if rejected or not copied back (for
 *     example after the check exhausted the pids limit), or if the run timed out. A null report
 *     with {@code timedOut=false} means the check failed.
 * @param timedOut true when the run hit its timeout and the container was killed
 */
public record SandboxResult(Path outputTar, String reportJson, boolean timedOut) {}
