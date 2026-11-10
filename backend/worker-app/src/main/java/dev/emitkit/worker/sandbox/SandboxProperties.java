package dev.emitkit.worker.sandbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code emitkit.sandbox.*}. {@code dockerHost} is the dedicated sandbox daemon socket;
 * {@code runtime} is {@code runsc} or {@code runc}; {@code image} is a placeholder default that
 * deployment overrides; {@code checkCommand} is the shell command that performs the check.
 */
@ConfigurationProperties("emitkit.sandbox")
public record SandboxProperties(
    @DefaultValue("unix:///run/emitkit-sandbox/docker.sock") String dockerHost,
    @DefaultValue("runsc") String runtime,
    @DefaultValue("4") int maxConcurrent,
    @DefaultValue("180s") Duration timeout,
    @DefaultValue("emitkit/toolchain:1") String image,
    @DefaultValue("/usr/local/bin/check.sh") String checkCommand) {}
