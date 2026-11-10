package dev.emitkit.worker.sandbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code emitkit.sandbox.*}; the sandbox daemon socket, runtime, limits and check command.
 */
@ConfigurationProperties("emitkit.sandbox")
public record SandboxProperties(
    @DefaultValue("unix:///run/emitkit-sandbox/docker.sock") String dockerHost,
    @DefaultValue("runsc") String runtime,
    @DefaultValue("4") int maxConcurrent,
    @DefaultValue("180s") Duration timeout,
    @DefaultValue("emitkit/toolchain:1") String image,
    @DefaultValue("/usr/local/bin/check.sh") String checkCommand) {}
