package dev.emitkit.ai;

import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import dev.emitkit.gen.core.DocsAiAssistant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * Builds a {@link ChatClient} or a {@link DocsAiAssistant} from a project's own API key. Each call
 * makes a new client, so no key is shared between projects and no chat model is registered as a
 * bean.
 */
public class ChatModelFactory {

  private static final String GEMINI = "gemini";

  /** The Gemini Developer API, the only endpoint project keys are sent to. */
  private static final String GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com";

  private final AiProperties props;

  public ChatModelFactory(AiProperties props) {
    this.props = Objects.requireNonNull(props, "props");
  }

  /**
   * Creates a chat client for one project.
   *
   * @param provider the model provider; only {@code gemini} is supported, case-insensitively
   * @param model the model name, or null or blank for the configured default
   * @param apiKey the project's API key; never logged or echoed in exception messages
   * @throws IllegalArgumentException if the provider is unsupported or the key is blank
   */
  public ChatClient create(String provider, @Nullable String model, String apiKey) {
    GoogleGenAiChatOptions options = optionsFor(provider, model);
    if (apiKey == null || apiKey.isBlank()) {
      throw new IllegalArgumentException("apiKey must not be blank");
    }
    // vertexAI(false) pins the Gemini Developer API, so GOOGLE_GENAI_USE_VERTEXAI cannot reroute
    // the key.
    Client genAi = genAiClient(apiKey);
    GoogleGenAiChatModel chatModel =
        GoogleGenAiChatModel.builder().genAiClient(genAi).options(options).build();
    return ChatClient.create(chatModel);
  }

  /**
   * Creates a docs assistant for one project, applying {@code emitkit.ai.timeout} and {@code
   * emitkit.ai.max-retries}.
   *
   * @throws IllegalArgumentException if the provider is unsupported or the key is blank
   */
  public DocsAiAssistant docsAssistant(String provider, @Nullable String model, String apiKey) {
    return docsAssistant(create(provider, model, apiKey));
  }

  DocsAiAssistant docsAssistant(ChatClient client) {
    return new GeminiDocsAiAssistant(client, props.timeout(), props.maxRetries());
  }

  /**
   * Builds the genAI client for one key. The SDK retries 408, 429, 500, 502, 503 and 504 responses
   * up to five times by default, which would bypass {@code emitkit.ai.max-retries}. So the client
   * makes one attempt and returns the error at once, and the assistant owns all retrying. The base
   * URL is pinned so that the SDK's {@code GOOGLE_GEMINI_BASE_URL} override cannot send the key
   * elsewhere.
   */
  Client genAiClient(String apiKey) {
    HttpOptions http =
        HttpOptions.builder()
            .baseUrl(GEMINI_API_BASE_URL)
            .retryOptions(
                // An empty status list means no status is retried, so no backoff sleep runs
                // before the error is returned. attempts(1) alone would still sleep once.
                HttpRetryOptions.builder().attempts(1).httpStatusCodes(List.of()).build())
            .build();
    return Client.builder().apiKey(apiKey).vertexAI(false).httpOptions(http).build();
  }

  /** Resolves the Gemini options for a model, falling back to the configured default. */
  GoogleGenAiChatOptions optionsFor(String provider, @Nullable String model) {
    requireGemini(provider);
    String resolved = model == null ? "" : model.strip();
    if (resolved.isEmpty()) {
      resolved = defaultModel();
    }
    return GoogleGenAiChatOptions.builder().model(resolved).build();
  }

  private static void requireGemini(String provider) {
    if (!GEMINI.equals(provider == null ? null : provider.toLowerCase(Locale.ROOT))) {
      throw new IllegalArgumentException("unsupported chat provider: " + provider);
    }
  }

  private String defaultModel() {
    String configured = props.gemini().defaultModel();
    if (configured == null || configured.isBlank()) {
      throw new IllegalStateException(
          "no Gemini model configured; set emitkit.ai.gemini.default-model");
    }
    return configured;
  }
}
