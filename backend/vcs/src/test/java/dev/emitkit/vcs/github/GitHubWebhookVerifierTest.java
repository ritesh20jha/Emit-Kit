package dev.emitkit.vcs.github;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GitHubWebhookVerifierTest {

  private static final String SECRET = "It's a Secret to Everybody";
  private static final byte[] BODY = "Hello, World!".getBytes(UTF_8);
  private static final String VALID_SIGNATURE =
      "sha256=757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";
  private static final String VALID_HEX =
      "757107ea0eb2509fc211221cce984b8a37570b6d7586c22c46f4379c8b043e17";

  private final GitHubWebhookVerifier v = new GitHubWebhookVerifier();

  // GitHub docs test vector: secret "It's a Secret to Everybody", payload "Hello, World!"
  @Test
  void knownVector_verifies() {
    assertThat(v.verify(BODY, VALID_SIGNATURE, SECRET)).isTrue();
  }

  @Test
  void changedBody_fails() {
    assertThat(v.verify("Hello, World?".getBytes(UTF_8), VALID_SIGNATURE, SECRET)).isFalse();
  }

  @Test
  void missingPrefix_fails() {
    assertThat(v.verify(BODY, VALID_HEX, SECRET)).isFalse();
  }

  @Test
  void wrongLengthHex_fails() {
    assertThat(v.verify(BODY, "sha256=" + VALID_HEX.substring(1), SECRET)).isFalse();
    assertThat(v.verify(BODY, VALID_SIGNATURE + "0", SECRET)).isFalse();
  }

  @Test
  void nonHexChars_fails() {
    String nonHex = "g" + VALID_HEX.substring(1);
    assertThat(v.verify(BODY, "sha256=" + nonHex, SECRET)).isFalse();
  }

  @Test
  void nullHeader_fails() {
    assertThat(v.verify(BODY, null, SECRET)).isFalse();
  }

  @Test
  void emptyHeader_fails() {
    assertThat(v.verify(BODY, "", SECRET)).isFalse();
  }

  @Test
  void prefixOnlyHeader_fails() {
    assertThat(v.verify(BODY, "sha256=", SECRET)).isFalse();
  }

  @Test
  void wrongSecret_fails() {
    assertThat(v.verify(BODY, VALID_SIGNATURE, "not the secret")).isFalse();
  }

  @Test
  void nullSecret_fails() {
    assertThat(v.verify(BODY, VALID_SIGNATURE, null)).isFalse();
  }

  @Test
  void emptySecret_fails() {
    assertThat(v.verify(BODY, VALID_SIGNATURE, "")).isFalse();
  }
}
