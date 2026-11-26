package dev.emitkit.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.emitkit.api.config.WebProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LoginSuccessHandlerTest {

  private final LoginSuccessHandler handler =
      new LoginSuccessHandler(event -> {}, new WebProperties("http://localhost:5173"));

  @ParameterizedTest
  @CsvSource({
    "/projects/x,/projects/x",
    "//evil.com,/",
    "https://evil.com,/",
    ",/",
    "/\\evil.com,/"
  })
  void redirectTarget(String next, String expectedPath) {
    assertThat(handler.target(next)).isEqualTo("http://localhost:5173" + expectedPath);
  }

  @ParameterizedTest
  @ValueSource(strings = {"/\tevil.com", "/\r\nSet-Cookie: x=1", "/\u0000projects", "/projects\\x"})
  void controlCharactersAndBackslashesFallBackToRoot(String next) {
    assertThat(handler.target(next)).isEqualTo("http://localhost:5173/");
  }
}
