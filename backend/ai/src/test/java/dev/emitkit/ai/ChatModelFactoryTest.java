package dev.emitkit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ChatModelFactoryTest {

  private final ChatModelFactory f =
      new ChatModelFactory(new AiProperties("gemini-default-x", Duration.ofSeconds(30), 1));

  @Test
  void emptyModel_usesConfiguredDefault() {
    var options = f.optionsFor("gemini", null);
    assertThat(options.getModel()).isEqualTo("gemini-default-x");
  }

  @Test
  void blankModel_usesConfiguredDefault() {
    var options = f.optionsFor("gemini", "   ");
    assertThat(options.getModel()).isEqualTo("gemini-default-x");
  }

  @Test
  void explicitModelWins() {
    var options = f.optionsFor("gemini", "gemini-explicit");
    assertThat(options.getModel()).isEqualTo("gemini-explicit");
  }

  @Test
  void providerMatchIsCaseInsensitive() {
    assertThat(f.optionsFor("GEMINI", null).getModel()).isEqualTo("gemini-default-x");
  }

  @Test
  void unknownProvider_rejected() {
    assertThatThrownBy(() -> f.create("openai", null, "secret-key-123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret-key-123");
  }

  @Test
  void blankApiKey_rejected() {
    assertThatThrownBy(() -> f.create("gemini", null, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingDefaultModel_failsWhenNoModelGiven() {
    var noDefault =
        new ChatModelFactory(
            new AiProperties(new AiProperties.Gemini(null), Duration.ofSeconds(30), 1));
    assertThatThrownBy(() -> noDefault.optionsFor("gemini", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("emitkit.ai.gemini.default-model");
  }

  @Test
  void create_returnsClientWithoutCallingTheNetwork() {
    ChatClient client = f.create("gemini", null, "test-key");
    assertThat(client).isNotNull();
  }
}
