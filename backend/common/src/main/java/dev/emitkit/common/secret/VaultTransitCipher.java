package dev.emitkit.common.secret;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.vault.VaultException;
import org.springframework.vault.client.VaultClientResponseException;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.VaultDecryptionResult;
import org.springframework.vault.support.VaultEncryptionResult;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.web.client.RestClientException;

/**
 * {@link SecretCipher} backed by a Vault Transit key. Values are encrypted with the row's {@link
 * SecretContext} as the Transit context. Plaintext and ciphertext never appear in exception
 * messages.
 */
public final class VaultTransitCipher implements SecretCipher {

  private static final int BAD_REQUEST = 400;
  private static final Pattern VERSION_PREFIX = Pattern.compile("^vault:v(\\d+):");
  private static final String PROBE_PLAINTEXT = "emitkit-key-version-probe";
  private static final SecretContext PROBE_CONTEXT = new SecretContext("health", "check", "0");

  private final VaultTransitOperations ops;
  private final String key;

  /**
   * Creates a cipher.
   *
   * @param ops the Transit operations to call
   * @param key the name of the Transit key
   */
  public VaultTransitCipher(VaultTransitOperations ops, String key) {
    this.ops = ops;
    this.key = key;
  }

  @Override
  public String encrypt(String plaintext, SecretContext ctx) {
    try {
      Ciphertext ciphertext = ops.encrypt(key, Plaintext.of(plaintext).with(transitContext(ctx)));
      return ciphertext.getCiphertext();
    } catch (VaultException | RestClientException e) {
      throw translate(e);
    }
  }

  @Override
  public String decrypt(String ciphertext, SecretContext ctx) {
    try {
      return ops.decrypt(key, Ciphertext.of(ciphertext).with(transitContext(ctx))).asString();
    } catch (VaultException | RestClientException e) {
      throw translate(e);
    }
  }

  @Override
  public List<String> decryptBatch(List<CipherItem> items) {
    if (items.isEmpty()) {
      return List.of();
    }
    List<Ciphertext> batch =
        items.stream()
            .map(item -> Ciphertext.of(item.ciphertext()).with(transitContext(item.ctx())))
            .toList();
    List<VaultDecryptionResult> results;
    try {
      results = ops.decrypt(key, batch);
    } catch (VaultException | RestClientException e) {
      throw translate(e);
    }
    return results.stream().map(VaultTransitCipher::plaintextOf).toList();
  }

  /**
   * Reads the version from the prefix of a probe encryption. Vault encrypts with the latest key
   * version, so this needs only the encrypt permission, not a read of the key.
   */
  @Override
  public int latestKeyVersion() {
    return versionOf(encrypt(PROBE_PLAINTEXT, PROBE_CONTEXT));
  }

  @Override
  public List<String> rewrapBatch(List<CipherItem> items) {
    if (items.isEmpty()) {
      return List.of();
    }
    List<Ciphertext> batch =
        items.stream()
            .map(item -> Ciphertext.of(item.ciphertext()).with(transitContext(item.ctx())))
            .toList();
    List<VaultEncryptionResult> results;
    try {
      results = ops.rewrap(key, batch);
    } catch (VaultException | RestClientException e) {
      throw translate(e);
    }
    return results.stream().map(VaultTransitCipher::ciphertextOf).toList();
  }

  private static VaultTransitContext transitContext(SecretContext ctx) {
    return VaultTransitContext.builder().context(ctx.bytes()).build();
  }

  private static String plaintextOf(VaultDecryptionResult result) {
    if (!result.isSuccessful()) {
      throw new AppException(
          ErrorCode.INTERNAL, "secret decryption failed", Map.of(), result.getCause());
    }
    return result.getAsString();
  }

  /** Fails without echoing the ciphertext: it is a backend response, not a value to log. */
  private static int versionOf(String ciphertext) {
    Matcher matcher = VERSION_PREFIX.matcher(ciphertext);
    if (!matcher.find()) {
      throw new AppException(
          ErrorCode.INTERNAL, "secret backend returned no key version", Map.of(), null);
    }
    return Integer.parseInt(matcher.group(1));
  }

  private static String ciphertextOf(VaultEncryptionResult result) {
    if (!result.isSuccessful()) {
      throw new AppException(
          ErrorCode.INTERNAL, "secret rewrap failed", Map.of(), result.getCause());
    }
    return result.get().getCiphertext();
  }

  /**
   * Vault rejects a request it cannot process (such as a context mismatch) with 400, which is a bug
   * in the caller. Every other failure (connection refused, 5xx, sealed) is a backend outage.
   */
  private static RuntimeException translate(RuntimeException e) {
    if (e instanceof VaultClientResponseException response
        && response.getStatusCode().value() == BAD_REQUEST) {
      return new AppException(ErrorCode.INTERNAL, "secret cipher request rejected", Map.of(), e);
    }
    return new SecretBackendException("secret backend unavailable", e);
  }
}
