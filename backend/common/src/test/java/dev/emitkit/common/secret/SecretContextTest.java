package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretContextTest {

  @Test
  void encoded_isBase64OfUtf8TableColumnRow() {
    var ctx = new SecretContext("projects", "webhook_secret_enc", "row-1");

    assertThat(ctx.encoded())
        .isEqualTo(
            Base64.getEncoder()
                .encodeToString(
                    "projects:webhook_secret_enc:row-1".getBytes(StandardCharsets.UTF_8)));
  }
}
