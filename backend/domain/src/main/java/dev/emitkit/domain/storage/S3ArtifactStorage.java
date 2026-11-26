package dev.emitkit.domain.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/** {@link ArtifactStorage} over the S3 API. Works with RustFS and other S3-compatible servers. */
public final class S3ArtifactStorage implements ArtifactStorage {

  private final S3Client s3;
  private final String bucket;

  public S3ArtifactStorage(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  /**
   * Builds a client for a non-AWS S3 endpoint: path-style access, static credentials, and checksums
   * only where the server requires them. The caller owns the returned client and must close it.
   */
  public static S3Client createClient(StorageProperties properties) {
    return S3Client.builder()
        .endpointOverride(URI.create(properties.endpoint()))
        .region(Region.of(properties.region()))
        .credentialsProvider(
            StaticCredentialsProvider.create(
                AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
        .forcePathStyle(true)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build();
  }

  @Override
  public void put(String key, Path file) {
    call(
        "put " + key,
        () -> {
          s3.putObject(
              PutObjectRequest.builder().bucket(bucket).key(key).build(),
              RequestBody.fromFile(file));
          return null;
        });
  }

  @Override
  public InputStream get(String key) {
    return call(
        "get " + key,
        () -> {
          try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
          } catch (NoSuchKeyException e) {
            throw new NoSuchArtifactException(key, e);
          }
        });
  }

  @Override
  public void delete(String key) {
    // S3 DeleteObject succeeds for a missing key, so no special case is needed.
    call(
        "delete " + key,
        () -> {
          s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
          return null;
        });
  }

  @Override
  public List<String> listOlderThan(String prefix, Instant cutoff) {
    return call(
        "list " + prefix,
        () -> {
          List<String> keys = new ArrayList<>();
          // The paginator fetches further pages lazily, so this loop runs inside the mapping.
          for (S3Object object :
              s3.listObjectsV2Paginator(
                      ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
                  .contents()) {
            if (object.lastModified().isBefore(cutoff)) {
              keys.add(object.key());
            }
          }
          return keys;
        });
  }

  /**
   * Runs one S3 operation. Server errors (5xx) and transport failures become StorageException;
   * other service errors (4xx) propagate as the SDK throws them.
   */
  private static <T> T call(String operation, Supplier<T> action) {
    try {
      return action.get();
    } catch (AwsServiceException e) {
      if (e.statusCode() >= 500) {
        throw new StorageException("S3 " + operation + " failed with HTTP " + e.statusCode(), e);
      }
      throw e;
    } catch (SdkClientException e) {
      throw new StorageException("S3 " + operation + " failed", e);
    }
  }
}
