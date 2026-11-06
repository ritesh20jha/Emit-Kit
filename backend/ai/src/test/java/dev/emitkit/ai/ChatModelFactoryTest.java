package dev.emitkit.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.ApiClient;
import com.google.genai.Client;
import com.sun.net.httpserver.HttpServer;
import dev.emitkit.spec.model.Operation;
import dev.emitkit.spec.model.Tag;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class ChatModelFactoryTest {

  private static final Tag PETS = new Tag("pets", "Pet operations");

  private static final Operation LIST_PETS =
      new Operation(
          "listPets",
          false,
          "GET",
          "/pets",
          "pets",
          null,
          null,
          List.of(),
          null,
          Map.of(),
          List.of(),
          false);

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

  @Test
  void genAiClient_pinsGeminiDeveloperApiBaseUrl() throws Exception {
    Client client = f.genAiClient("test-key");

    assertThat(apiClientOf(client).httpOptions().baseUrl())
        .hasValue("https://generativelanguage.googleapis.com");
  }

  @Test
  void genAiClient_disablesSdkRetryOptions() throws Exception {
    Client client = f.genAiClient("test-key");

    assertThat(apiClientOf(client).httpOptions().retryOptions().orElseThrow().attempts())
        .hasValue(1);
  }

  @Test
  void genAiClient_sendsOneRequestAndReturnsServerErrorAtOnce() throws Exception {
    var hits = new AtomicInteger();
    HttpServer server =
        HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.start();
    try {
      Client client = f.genAiClient("test-key");
      var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";

      try (Response response =
          apiClientOf(client)
              .httpClient()
              .newCall(new Request.Builder().url(url).build())
              .execute()) {
        assertThat(response.code()).isEqualTo(503);
      }
    } finally {
      server.stop(0);
    }

    assertThat(hits.get()).isEqualTo(1);
  }

  @Test
  void docsAssistant_returnsGeminiAssistant() {
    assertThat(f.docsAssistant("gemini", null, "test-key"))
        .isInstanceOf(GeminiDocsAiAssistant.class);
  }

  @Test
  void docsAssistant_unknownProvider_andBlankKey_rejected() {
    assertThatThrownBy(() -> f.docsAssistant("openai", null, "secret-key-123"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("secret-key-123");
    assertThatThrownBy(() -> f.docsAssistant("gemini", null, " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void docsAssistant_appliesConfiguredMaxRetries() {
    var props = new AiProperties("gemini-default-x", Duration.ofSeconds(30), 3);
    var fake = FakeChatModel.failing();
    var assistant = new ChatModelFactory(props).docsAssistant(ChatClient.create(fake));

    assistant.examples(PETS, List.of(LIST_PETS));

    assertThat(fake.calls()).isEqualTo(4);
  }

  @Test
  void docsAssistant_appliesConfiguredTimeout() {
    var props = new AiProperties("gemini-default-x", Duration.ofMillis(100), 0);
    var fake = FakeChatModel.delaying(Duration.ofSeconds(2));
    var assistant = new ChatModelFactory(props).docsAssistant(ChatClient.create(fake));

    long started = System.nanoTime();
    var out = assistant.examples(PETS, List.of(LIST_PETS));
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

    assertThat(out).isEmpty();
    assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
  }

  // The SDK exposes no accessor for its ApiClient, so the test reads the private field.
  private static ApiClient apiClientOf(Client client) throws ReflectiveOperationException {
    Field field = Client.class.getDeclaredField("apiClient");
    field.setAccessible(true);
    return (ApiClient) field.get(client);
  }
}
