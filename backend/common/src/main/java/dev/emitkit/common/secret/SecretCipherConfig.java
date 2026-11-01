package dev.emitkit.common.secret;

import java.util.Arrays;
import java.util.Set;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.LifecycleAwareSessionManager;
import org.springframework.vault.authentication.SessionManager;
import org.springframework.vault.client.RestClientBuilder;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link SecretCipher} selected by {@code emitkit.vault.cipher}.
 *
 * <p>Vault is never contacted at startup. The AppRole login happens on the first Transit call, so
 * an application starts and reports {@code vault} health DOWN while Vault is unavailable.
 */
@AutoConfiguration
@EnableConfigurationProperties(VaultProperties.class)
public class SecretCipherConfig implements DisposableBean {

  private static final Set<String> LOCAL_FORBIDDEN_PROFILES = Set.of("prod", "staging");

  /** Runs token renewal. Created only for the Vault cipher; stopped when the context closes. */
  private ThreadPoolTaskScheduler sessionScheduler;

  @Bean
  @ConditionalOnMissingBean(SecretCipher.class)
  SecretCipher secretCipher(VaultProperties props, Environment environment) {
    return switch (props.cipher()) {
      case VAULT -> vaultCipher(props);
      case LOCAL -> localCipher(environment);
    };
  }

  @Override
  public void destroy() {
    if (sessionScheduler != null) {
      sessionScheduler.shutdown();
    }
  }

  private SecretCipher vaultCipher(VaultProperties props) {
    String uri = required(props.uri(), "emitkit.vault.uri");
    String roleId = required(props.roleId(), "emitkit.vault.role-id");

    VaultEndpoint endpoint = VaultEndpoint.from(uri);
    RestClientBuilder clientBuilder =
        RestClientBuilder.builder()
            .endpoint(endpoint)
            .requestFactory(new SimpleClientHttpRequestFactory());
    RestClient restClient = clientBuilder.build();
    AppRoleAuthenticationOptions options =
        AppRoleAuthenticationOptions.builder()
            .roleId(AppRoleAuthenticationOptions.RoleId.provided(roleId))
            .secretId(AppRoleAuthenticationOptions.SecretId.provided(props.secretId()))
            .build();

    // A plain SimpleSessionManager would keep the first token forever. The lifecycle-aware one
    // renews it and logs in lazily, on the first request.
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("vault-session-");
    scheduler.setDaemon(true);
    scheduler.initialize();
    this.sessionScheduler = scheduler;

    SessionManager sessions =
        new LifecycleAwareSessionManager(
            new AppRoleAuthentication(options, restClient), scheduler, restClient);
    VaultTemplate vault = new VaultTemplate(clientBuilder, sessions);
    return new VaultTransitCipher(vault.opsForTransit(), props.transitKey());
  }

  private static SecretCipher localCipher(Environment environment) {
    // getActiveProfiles() also reads the spring.profiles.active property.
    boolean forbidden =
        Arrays.stream(environment.getActiveProfiles()).anyMatch(LOCAL_FORBIDDEN_PROFILES::contains);
    if (forbidden) {
      throw new IllegalStateException("LocalAesCipher is not allowed with profile prod/staging");
    }
    return new LocalAesCipher();
  }

  private static String required(String value, String property) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(property + " is required when emitkit.vault.cipher=vault");
    }
    return value;
  }
}
