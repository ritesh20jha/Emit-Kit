package dev.emitkit.common.secret;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for the secret cipher, bound from {@code emitkit.vault.*}.
 *
 * @param uri the Vault server URI, such as {@code https://vault.internal:8200}
 * @param roleId the AppRole role id
 * @param secretIdFile path to the file holding the AppRole secret id, such as a Docker secret; the
 *     secret id is never a plain property
 * @param transitKey the name of the Transit key that encrypts secrets
 * @param cipher which {@link SecretCipher} to wire
 */
@ConfigurationProperties("emitkit.vault")
public record VaultProperties(
    String uri,
    String roleId,
    String secretIdFile,
    @DefaultValue("emitkit-secrets") String transitKey,
    @DefaultValue("vault") CipherMode cipher) {

  /** The cipher implementation to wire. */
  public enum CipherMode {
    /** Vault Transit, through AppRole login. */
    VAULT,
    /** {@link LocalAesCipher}, for tests only. */
    LOCAL
  }

  /**
   * Reads the AppRole secret id from {@link #secretIdFile}, with surrounding whitespace removed.
   *
   * @throws IllegalStateException if the file is not configured, cannot be read or is empty
   */
  public String secretId() {
    if (secretIdFile == null) {
      throw new IllegalStateException("emitkit.vault.secret-id-file is not set");
    }
    String secretId;
    try {
      secretId = Files.readString(Path.of(secretIdFile)).strip();
    } catch (IOException e) {
      throw new IllegalStateException("emitkit.vault.secret-id-file cannot be read", e);
    }
    if (secretId.isEmpty()) {
      throw new IllegalStateException("emitkit.vault.secret-id-file is empty");
    }
    return secretId;
  }
}
