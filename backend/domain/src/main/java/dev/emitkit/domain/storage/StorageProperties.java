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
    @DefaultValue("us-east-1") String region) {}
