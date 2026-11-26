package dev.emitkit.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class RunCursorTest {

  private static final String ID = "01JZ0000000000000000000000";

  @Test
  void encodeThenDecode_roundTripsIncludingNanoseconds() {
    Instant createdAt = Instant.parse("2026-11-01T10:00:00.123456789Z");

    String token = RunCursor.encode(createdAt, ID);

    assertThat(RunCursor.decode(token)).isEqualTo(new RunCursor(createdAt, ID));
  }

  @Test
  void decode_rejectsTokenWithoutSeparator() {
    assertThatThrownBy(() -> RunCursor.decode("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RunCursor.decode(base64("nope")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_rejectsTokenThatIsNotBase64() {
    assertThatThrownBy(() -> RunCursor.decode("!!!")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void decode_rejectsBadTimestamp() {
    assertThatThrownBy(() -> RunCursor.decode(base64("not-a-time|" + ID)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Malformed run cursor");
  }

  private static String base64(String raw) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }
}
