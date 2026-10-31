package dev.emitkit.domain.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** S3 (RustFS) connection settings, bound from {@code emitkit.storage.*}. */
@ConfigurationProperties("emitkit.storage")
public record StorageProperties(
    String endpoint,
    String bucket,
    String accessKey,
    String secretKey,
    @DefaultValue("us-east-1") String region) {

  /** Same as the generated record string, except the secret is masked so logs never leak it. */
  @Override
  public String toString() {
    return "StorageProperties[endpoint="
        + endpoint
        + ", bucket="
        + bucket
        + ", accessKey="
        + accessKey
        + ", secretKey=***, region="
        + region
        + "]";
  }
}
