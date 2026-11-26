package dev.emitkit.worker.sandbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class DockerSandboxRunnerTest {

  @Test
  void requestTimeoutAtSweeperBound_isRejectedBeforeAnyDockerCall() throws IOException {
    var properties =
        new SandboxProperties(
            "unix:///nonexistent-emitkit/docker.sock",
            "runsc",
            4,
            Duration.ofSeconds(180),
            "emitkit/toolchain:1",
            "/usr/local/bin/check.sh");
    var req = new SandboxRequest("emitkit/toolchain:1", Path.of("in.tar"), Duration.ofMinutes(9));
    try (DockerClient docker =
        SandboxConfig.newDockerClient("unix:///nonexistent-emitkit/docker.sock")) {
      var runner = new DockerSandboxRunner(docker, properties);

      assertThatThrownBy(() -> runner.run(req, new CancellationToken()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("sandbox timeout must be under");
    }
  }
}
