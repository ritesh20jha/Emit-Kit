package dev.emitkit.vcs;

/** Checks that a webhook delivery was signed with the shared secret. */
public interface WebhookVerifier {

  /**
   * Returns true when {@code signatureHeader} is a valid signature of {@code body} under {@code
   * secret}.
   */
  boolean verify(byte[] body, String signatureHeader, String secret);
}
