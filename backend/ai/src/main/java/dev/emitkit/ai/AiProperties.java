package dev.emitkit.ai;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the docs AI assistant, bound under {@code emitkit.ai}.
 *
 * <p>{@code emitkit.ai.gemini.default-model} is used when a project sets no model. The factory
 * fails if it is missing. {@link ChatModelFactory#docsAssistant(String, String, String)} applies
 * {@code emitkit.ai.timeout} to each model call and {@code emitkit.ai.max-retries} to the retries
 * after a failed call. Nothing else reads these two settings.
 */
@ConfigurationProperties("emitkit.ai")
public record AiProperties(
    @DefaultValue Gemini gemini,
    @DefaultValue("30s") Duration timeout,
    @DefaultValue("1") int maxRetries) {

  @ConstructorBinding
  public AiProperties {}

  /** Creates settings with a flat Gemini default model. */
  public AiProperties(@Nullable String geminiDefaultModel, Duration timeout, int maxRetries) {
    this(new Gemini(geminiDefaultModel), timeout, maxRetries);
  }

  /** Gemini-specific settings. */
  public record Gemini(@Nullable String defaultModel) {}
}
