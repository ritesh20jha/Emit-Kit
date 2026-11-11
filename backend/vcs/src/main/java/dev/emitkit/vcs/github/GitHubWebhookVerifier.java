package dev.emitkit.vcs.github;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.emitkit.vcs.WebhookVerifier;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies GitHub's {@code X-Hub-Signature-256} header: {@code sha256=} followed by 64 hex digits,
 * the HMAC-SHA256 of the raw body keyed with the webhook secret. A malformed header is a failed
 * verification, never an exception.
 */
public final class GitHubWebhookVerifier implements WebhookVerifier {

  private static final String PREFIX = "sha256=";
  private static final int HEX_LENGTH = 64;
  private static final String ALGORITHM = "HmacSHA256";

  @Override
  public boolean verify(byte[] body, String signatureHeader, String secret) {
    // Fail closed: a project without a secret must not verify, and an empty key would let anyone
    // compute a valid signature. Also keeps the "never throws" contract when the secret is absent.
    if (secret == null || secret.isEmpty()) {
      return false;
    }
    if (signatureHeader == null || !signatureHeader.startsWith(PREFIX)) {
      return false;
    }
    String hex = signatureHeader.substring(PREFIX.length());
    if (hex.length() != HEX_LENGTH || !allHexDigits(hex)) {
      return false;
    }
    byte[] provided = HexFormat.of().parseHex(hex);
    return MessageDigest.isEqual(hmac(secret, body), provided);
  }

  private static boolean allHexDigits(String hex) {
    for (int i = 0; i < hex.length(); i++) {
      if (!HexFormat.isHexDigit(hex.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static byte[] hmac(String secret, byte[] body) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(secret.getBytes(UTF_8), ALGORITHM));
      return mac.doFinal(body);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(ALGORITHM + " is not usable", e);
    }
  }
}
