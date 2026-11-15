package dev.emitkit.api.config;

import java.security.SecureRandom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The randomness source for secrets that the API generates, such as webhook secrets. */
@Configuration(proxyBeanMethods = false)
public class RandomConfig {

  @Bean
  SecureRandom secureRandom() {
    return new SecureRandom();
  }
}
