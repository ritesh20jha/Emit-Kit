package dev.emitkit.ai;

import com.google.genai.Client;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;

/**
 * Builds a {@link ChatClient} from a project's own API key. Each call makes a new client, so no key
 * is shared between projects and no chat model is registered as a bean.
 */
public class ChatModelFactory {

  private static final String GEMINI = "gemini";

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
    Client genAi = Client.builder().apiKey(apiKey).vertexAI(false).build();
    GoogleGenAiChatModel chatModel =
        GoogleGenAiChatModel.builder().genAiClient(genAi).options(options).build();
    return ChatClient.create(chatModel);
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
