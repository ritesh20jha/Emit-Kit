package dev.emitkit.worker.steps;

import dev.emitkit.ai.AiProperties;
import dev.emitkit.ai.ChatModelFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfig {

  @Bean
  ChatModelFactory chatModelFactory(AiProperties props) {
    return new ChatModelFactory(props);
  }
}
