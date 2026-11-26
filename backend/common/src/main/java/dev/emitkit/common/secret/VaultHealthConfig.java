package dev.emitkit.common.secret;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;

/** Registers the {@code vault} health component after {@link SecretCipherConfig} has run. */
@AutoConfiguration(after = SecretCipherConfig.class)
@ConditionalOnClass(HealthIndicator.class)
@ConditionalOnBean(SecretCipher.class)
public class VaultHealthConfig {

  @Bean("vault")
  VaultHealthIndicator vault(SecretCipher cipher) {
    return new VaultHealthIndicator(cipher);
  }
}
