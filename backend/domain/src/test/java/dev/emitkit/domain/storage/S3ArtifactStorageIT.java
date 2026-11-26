package dev.emitkit.domain.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;

class S3ArtifactStorageIT {

  private static final String BUCKET = "emitkit-artifacts";
  private static final RustFsContainer RUSTFS = new RustFsContainer();

  private static S3Client s3;
  private static ArtifactStorage storage;

  @BeforeAll
  static void startRustFsAndCreateBucket() {
    RUSTFS.start();
    s3 =
        S3ArtifactStorage.createClient(
            new StorageProperties(
                RUSTFS.endpoint(),
                BUCKET,
                RustFsContainer.ACCESS_KEY,
                RustFsContainer.SECRET_KEY,
                "us-east-1"));
    s3.createBucket(b -> b.bucket(BUCKET));
    storage = new S3ArtifactStorage(s3, BUCKET);
  }

  @AfterAll
  static void stopRustFs() {
    s3.close();
    RUSTFS.stop();
  }

  @Test
  void put_thenGet_returnsSameBytes(@TempDir Path dir) throws Exception {
    byte[] content = new byte[1024 * 1024];
    new SecureRandom().nextBytes(content);
    Path file = Files.write(dir.resolve("run.zip"), content);

    storage.put("roundtrip/run.zip", file);

    try (InputStream in = storage.get("roundtrip/run.zip")) {
      assertThat(in.readAllBytes()).isEqualTo(content);
    }
  }

  @Test
  void get_missing_throwsNoSuchArtifact() {
    assertThatThrownBy(() -> storage.get("missing/none.zip"))
        .isInstanceOf(NoSuchArtifactException.class);
  }

  @Test
  void delete_missing_isNoOp() {
    assertThatCode(() -> storage.delete("missing/none.zip")).doesNotThrowAnyException();
  }

  @Test
  void listOlderThan_returnsOnlyOldKeys(@TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("a.zip"), "payload");
    // S3 reports lastModified in whole seconds from the server clock. A one-minute margin on
    // both cutoffs keeps the boundary checks clear of that truncation and of clock skew.
    Instant beforeFirstPut = Instant.now().minus(Duration.ofMinutes(1));
    storage.put("list/a/1", file);
    storage.put("list/a/2", file);

    assertThat(storage.listOlderThan("list/a/", Instant.now().plus(Duration.ofMinutes(1))))
        .containsExactlyInAnyOrder("list/a/1", "list/a/2");
    assertThat(storage.listOlderThan("list/a/", beforeFirstPut)).isEmpty();
  }

  @Test
  void put_overwritesExistingKey(@TempDir Path dir) throws Exception {
    Path first = Files.writeString(dir.resolve("first.txt"), "first");
    Path second = Files.writeString(dir.resolve("second.txt"), "second");

    storage.put("overwrite/x.zip", first);
    storage.put("overwrite/x.zip", second);

    try (InputStream in = storage.get("overwrite/x.zip")) {
      assertThat(in.readAllBytes()).isEqualTo("second".getBytes(StandardCharsets.UTF_8));
    }
  }
}
