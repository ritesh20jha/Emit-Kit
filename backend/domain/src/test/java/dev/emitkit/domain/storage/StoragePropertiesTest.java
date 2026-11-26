package dev.emitkit.domain.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StoragePropertiesTest {

  @Test
  void toString_masksSecretKey() {
    StorageProperties props =
        new StorageProperties(
            "http://rustfs:9000", "emitkit-artifacts", "emitkit", "s3cr3t", "us-east-1");

    assertThat(props.toString())
        .doesNotContain("s3cr3t")
        .isEqualTo(
            "StorageProperties[endpoint=http://rustfs:9000, bucket=emitkit-artifacts, "
                + "accessKey=emitkit, secretKey=***, region=us-east-1]");
  }
}
