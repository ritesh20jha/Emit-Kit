package dev.emitkit.common.secret;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link SecretCipher} that encrypts locally with AES-256-GCM and a fixed, public test key. The
 * {@link SecretContext} is bound as the additional authenticated data, so a ciphertext only
 * decrypts under the context it was encrypted with.
 *
 * <p><b>For unit tests only.</b> The key is a constant in this source file, so anyone can decrypt
 * what this cipher produces. It must never be used in deployed environments; a profile guard
 * enforces that when the cipher is wired into Spring.
 */
public class LocalAesCipher implements SecretCipher {

  private static final String PREFIX = "local:v1:";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final String KEY_ALGORITHM = "AES";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int TAG_BYTES = TAG_BITS / 8;
  private static final String DECRYPTION_FAILED = "secret decryption failed";
  private static final byte[] TEST_KEY =
      HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f");
  private static final SecureRandom RANDOM = new SecureRandom();

  @Override
  public String encrypt(String plaintext, SecretContext ctx) {
    byte[] iv = new byte[IV_BYTES];
    RANDOM.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(ctx));
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] ivAndCiphertext =
          ByteBuffer.allocate(IV_BYTES + ciphertext.length).put(iv).put(ciphertext).array();
      return PREFIX + Base64.getEncoder().encodeToString(ivAndCiphertext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("local cipher encryption failed", e);
    }
  }

  @Override
  public String decrypt(String ciphertext, SecretContext ctx) {
    if (!ciphertext.startsWith(PREFIX)) {
      throw decryptionFailed(null);
    }
    byte[] ivAndCiphertext;
    try {
      ivAndCiphertext = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
    } catch (IllegalArgumentException e) {
      throw decryptionFailed(e);
    }
    if (ivAndCiphertext.length < IV_BYTES + TAG_BYTES) {
      throw decryptionFailed(null);
    }
    byte[] iv = Arrays.copyOfRange(ivAndCiphertext, 0, IV_BYTES);
    byte[] body = Arrays.copyOfRange(ivAndCiphertext, IV_BYTES, ivAndCiphertext.length);
    try {
      Cipher cipher = Cipher.getInstance(TRANSFORMATION);
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(ctx));
      return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw decryptionFailed(e);
    }
  }

  @Override
  public List<String> decryptBatch(List<CipherItem> items) {
    return items.stream().map(item -> decrypt(item.ciphertext(), item.ctx())).toList();
  }

  private static SecretKeySpec key() {
    return new SecretKeySpec(TEST_KEY, KEY_ALGORITHM);
  }

  private static byte[] aad(SecretContext ctx) {
    return ctx.encoded().getBytes(StandardCharsets.UTF_8);
  }

  /** The message never includes the plaintext or the ciphertext. */
  private static AppException decryptionFailed(Throwable cause) {
    return new AppException(ErrorCode.INTERNAL, DECRYPTION_FAILED, Map.of(), cause);
  }
}
