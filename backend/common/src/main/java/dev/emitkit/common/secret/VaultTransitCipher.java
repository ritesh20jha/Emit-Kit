package dev.emitkit.common.secret;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.util.List;
import java.util.Map;
import org.springframework.vault.VaultException;
import org.springframework.vault.client.VaultClientResponseException;
import org.springframework.vault.core.VaultTransitOperations;
import org.springframework.vault.support.Ciphertext;
import org.springframework.vault.support.Plaintext;
import org.springframework.vault.support.VaultDecryptionResult;
import org.springframework.vault.support.VaultTransitContext;
import org.springframework.web.client.RestClientException;

/**
 * {@link SecretCipher} backed by a Vault Transit key. Values are encrypted with the row's {@link
 * SecretContext} as the Transit context. Plaintext and ciphertext never appear in exception
 * messages.
 */
public final class VaultTransitCipher implements SecretCipher {

  private static final int BAD_REQUEST = 400;

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
