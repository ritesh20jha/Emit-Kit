package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalAesCipherTest {

  private final SecretContext ctx =
      new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000A");
  private final SecretContext otherCtx =
      new SecretContext("projects", "webhook_secret_enc", "01J0000000000000000000000B");

  @Test
  void roundTrip_andContextBound() {
    var c = new LocalAesCipher();
    String ct = c.encrypt("x", ctx);
    assertThat(ct).startsWith("local:v1:");
    assertThat(c.decrypt(ct, ctx)).isEqualTo("x");
    assertThatThrownBy(() -> c.decrypt(ct, otherCtx))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  @Test
  void decryptBatch_keepsOrder_acrossContexts() {
    var c = new LocalAesCipher();
    var ctxA = new SecretContext("projects", "webhook_secret_enc", "A");
    var ctxB = new SecretContext("projects", "webhook_secret_enc", "B");
    var ctxC = new SecretContext("projects", "webhook_secret_enc", "C");
    List<CipherItem> items =
        List.of(
            new CipherItem(c.encrypt("alpha", ctxA), ctxA),
            new CipherItem(c.encrypt("bravo", ctxB), ctxB),
            new CipherItem(c.encrypt("charlie", ctxC), ctxC));

    assertThat(c.decryptBatch(items)).containsExactly("alpha", "bravo", "charlie");
  }

  @Test
  void encrypt_usesRandomIv_soSamePlaintextGivesDifferentCiphertexts() {
    var c = new LocalAesCipher();
    assertThat(c.encrypt("x", ctx)).isNotEqualTo(c.encrypt("x", ctx));
  }

  @Test
  void tamperedCiphertext_throwsInternal() {
    var c = new LocalAesCipher();
    String ct = c.encrypt("x", ctx);
    byte[] raw = Base64.getDecoder().decode(ct.substring("local:v1:".length()));
    raw[raw.length - 1] ^= 0x01;
    String tampered = "local:v1:" + Base64.getEncoder().encodeToString(raw);

    assertThatThrownBy(() -> c.decrypt(tampered, ctx))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  @Test
  void garbageCiphertext_throwsInternal() {
    var c = new LocalAesCipher();

    assertThatThrownBy(() -> c.decrypt("local:v1:!!not-base64!!", ctx))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }

  @Test
  void wrongPrefix_throwsInternal() {
    var c = new LocalAesCipher();
    String ct = c.encrypt("x", ctx);
    String wrongPrefix = "vault:v1:" + ct.substring("local:v1:".length());

    assertThatThrownBy(() -> c.decrypt(wrongPrefix, ctx))
        .isInstanceOfSatisfying(
            AppException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INTERNAL));
  }
}
