package dev.emitkit.common.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  void nullOrBlankPart_isRejected(String bad) {
    assertThatThrownBy(() -> new SecretContext(bad, "webhook_secret_enc", "row-1"))
        .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretContext("projects", bad, "row-1"))
        .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
    assertThatThrownBy(() -> new SecretContext("projects", "webhook_secret_enc", bad))
        .isInstanceOfAny(NullPointerException.class, IllegalArgumentException.class);
  }
}
