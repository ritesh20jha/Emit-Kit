package dev.emitkit.worker.steps;

import dev.emitkit.ai.AiProperties;
import dev.emitkit.ai.ChatModelFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@code emitkit.ai} and provides the factory that builds a project's docs assistant. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

  @Bean
  ChatModelFactory chatModelFactory(AiProperties props) {
    return new ChatModelFactory(props);
  }
}
