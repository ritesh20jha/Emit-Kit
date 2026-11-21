package dev.emitkit.worker.config;

import dev.emitkit.domain.storage.ArtifactStorage;
import dev.emitkit.domain.storage.S3ArtifactStorage;
import dev.emitkit.domain.storage.StorageProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The artifact store that run packages are written to, configured by {@code emitkit.storage.*}. The
 * client makes no call until the first artifact is stored, so startup never needs the bucket.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class WorkerStorageConfig {

  @Bean
  S3Client s3Client(StorageProperties properties) {
    return S3ArtifactStorage.createClient(properties);
  }

  @Bean
  ArtifactStorage artifactStorage(S3Client s3Client, StorageProperties properties) {
    return new S3ArtifactStorage(s3Client, properties.bucket());
  }
}
