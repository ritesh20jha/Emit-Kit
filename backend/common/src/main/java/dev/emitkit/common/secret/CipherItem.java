package dev.emitkit.common.secret;

/**
 * One ciphertext with the context it was encrypted under, for batch decryption.
 *
 * @param ciphertext the Vault ciphertext, such as {@code vault:v1:...}
 * @param ctx the context the ciphertext was encrypted under
 */
public record CipherItem(String ciphertext, SecretContext ctx) {}
