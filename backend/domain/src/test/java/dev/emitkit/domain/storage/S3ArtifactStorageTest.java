package dev.emitkit.domain.storage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Fast checks that need no container: nothing listens on port 1, so every call fails in transport.
 */
class S3ArtifactStorageTest {

  private static final StorageProperties UNREACHABLE =
      new StorageProperties(
          "http://localhost:1", "emitkit-artifacts", "emitkit", "secret", "us-east-1");

  @Test
  void transportFailure_mapsToStorageException(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("run.zip"), "payload");

    try (S3Client s3 = S3ArtifactStorage.createClient(UNREACHABLE)) {
      ArtifactStorage storage = new S3ArtifactStorage(s3, "emitkit-artifacts");

      assertThatThrownBy(() -> storage.put("artifacts/org/run.zip", file))
          .isInstanceOf(StorageException.class);
      assertThatThrownBy(() -> storage.get("artifacts/org/run.zip"))
          .isInstanceOf(StorageException.class);
      assertThatThrownBy(() -> storage.listOlderThan("artifacts/", Instant.now()))
          .isInstanceOf(StorageException.class);
    }
  }
}
