package dev.emitkit.common.secret;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Binds {@code emitkit.vault.*}. The AppRole secret id is read from a file, never a property. */
@ConfigurationProperties("emitkit.vault")
public record VaultProperties(
    String uri,
    String roleId,
    String secretIdFile,
    @DefaultValue("emitkit-secrets") String transitKey,
    @DefaultValue("vault") CipherMode cipher) {

  /** {@code LOCAL} is for tests only and is refused under prod and staging. */
  public enum CipherMode {
    VAULT,
    LOCAL
  }

  /** The trimmed secret id from {@link #secretIdFile}. */
  public String secretId() {
    if (secretIdFile == null) {
      throw new IllegalStateException("emitkit.vault.secret-id-file is not set");
    }
    try {
      String secretId = Files.readString(Path.of(secretIdFile)).strip();
      if (secretId.isEmpty()) {
        throw new IllegalStateException("emitkit.vault.secret-id-file is empty");
      }
      return secretId;
    } catch (IOException e) {
      throw new IllegalStateException("emitkit.vault.secret-id-file cannot be read", e);
    }
  }
}
