package dev.emitkit.domain.storage;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** RustFS started from the same pinned image and dev credentials as deploy/compose. */
final class RustFsContainer extends GenericContainer<RustFsContainer> {

  static final String ACCESS_KEY = "emitkit";
  static final String SECRET_KEY = "emitkit-secret";

  private static final int S3_PORT = 9000;

  RustFsContainer() {
    super(DockerImageName.parse("rustfs/rustfs:1.0.4"));
    withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
    withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
    withEnv("RUSTFS_VOLUMES", "/data");
    withExposedPorts(S3_PORT);
    waitingFor(Wait.forHttp("/health").forPort(S3_PORT).forStatusCode(200));
  }

  String endpoint() {
    return "http://" + getHost() + ":" + getMappedPort(S3_PORT);
  }
}
