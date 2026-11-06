package dev.emitkit.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AiPropertiesTest {

  @Test
  void bindsNestedGeminiDefaultModel_andAppliesDefaults() {
    AiProperties p = bind(Map.of("emitkit.ai.gemini.default-model", "gemini-x"));

    assertThat(p.gemini().defaultModel()).isEqualTo("gemini-x");
    assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(30));
    assertThat(p.maxRetries()).isEqualTo(1);
  }

  @Test
  void bindsExplicitTimeoutAndRetries() {
    AiProperties p =
        bind(
            Map.of(
                "emitkit.ai.gemini.default-model", "gemini-x",
                "emitkit.ai.timeout", "5s",
                "emitkit.ai.max-retries", "0"));

    assertThat(p.timeout()).isEqualTo(Duration.ofSeconds(5));
    assertThat(p.maxRetries()).isZero();
  }

  @Test
  void unboundGemini_yieldsNullDefaultModelNotNullGroup() {
    AiProperties p = bind(Map.of());

    assertThat(p.gemini()).isNotNull();
    assertThat(p.gemini().defaultModel()).isNull();
  }

  // bindOrCreate is the path @ConfigurationProperties beans take, so defaults apply with no props.
  private static AiProperties bind(Map<String, String> properties) {
    return new Binder(new MapConfigurationPropertySource(properties))
        .bindOrCreate("emitkit.ai", AiProperties.class);
  }
}
