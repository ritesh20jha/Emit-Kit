package dev.emitkit.common.secret;

import java.util.List;

/**
 * Encrypts and decrypts secret values, binding each one to the {@link SecretContext} it belongs to.
 */
public interface SecretCipher {

  /**
   * Encrypts a plaintext value.
   *
   * @return the ciphertext, prefixed with its key version, such as {@code vault:v1:...}
   * @throws SecretBackendException if the secret backend is unreachable, sealed or failing
   */
  String encrypt(String plaintext, SecretContext ctx);

  /**
   * Decrypts a ciphertext that was encrypted under the same context.
   *
   * @throws SecretBackendException if the secret backend is unreachable, sealed or failing
   * @throws dev.emitkit.common.error.AppException with {@code INTERNAL} if the ciphertext does not
   *     match the context
   */
  String decrypt(String ciphertext, SecretContext ctx);

  /**
   * Decrypts several values in one call to the backend.
   *
   * @return the plaintexts in the same order as {@code items}; empty if {@code items} is empty
   * @throws SecretBackendException if the secret backend is unreachable, sealed or failing
   * @throws dev.emitkit.common.error.AppException with {@code INTERNAL} if any item does not match
   *     its context
   */
  List<String> decryptBatch(List<CipherItem> items);
}
