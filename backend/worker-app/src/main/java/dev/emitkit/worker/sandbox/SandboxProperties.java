package dev.emitkit.worker.sandbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds {@code emitkit.sandbox.*}.
 *
 * @param dockerHost socket of the dedicated sandbox daemon (the SBX-01 decision: option B)
 * @param runtime OCI runtime for sandbox containers: {@code runsc} (gVisor) or {@code runc}
 * @param maxConcurrent sandbox containers one worker runs at once
 * @param timeout default run limit when a request does not set one
 * @param image placeholder default image when a request does not name one; set the real toolchain
 *     tag (spec 5.6 {@code emitkit/toolchain:{ver}}) in deployment config
 * @param checkCommand shell command the container runs for the check. It reads {@code /work/in.tar}
 *     and writes {@code /work/out.tar} and {@code /work/report.json}.
 */
@ConfigurationProperties("emitkit.sandbox")
public record SandboxProperties(
    @DefaultValue("unix:///run/emitkit-sandbox/docker.sock") String dockerHost,
    @DefaultValue("runsc") String runtime,
    @DefaultValue("4") int maxConcurrent,
    @DefaultValue("180s") Duration timeout,
    @DefaultValue("emitkit/toolchain:1") String image,
    @DefaultValue("/usr/local/bin/check.sh") String checkCommand) {}
