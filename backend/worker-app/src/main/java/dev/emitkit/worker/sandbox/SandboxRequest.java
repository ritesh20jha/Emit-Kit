package dev.emitkit.worker.sandbox;

import java.nio.file.Path;
import java.time.Duration;

/**
 * One sandbox run.
 *
 * @param image the toolchain image; null uses {@code emitkit.sandbox.image}
 * @param inputTar tar of the files to check, copied to {@code /work/in.tar}
 * @param timeout limit on the running container; null uses {@code emitkit.sandbox.timeout}
 */
public record SandboxRequest(String image, Path inputTar, Duration timeout) {}
