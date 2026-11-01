package dev.emitkit.common.secret;

import java.time.Duration;
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

/** Wires the {@link SecretCipher} chosen by {@code emitkit.vault.cipher}. Lazy: no startup call. */
@AutoConfiguration
@EnableConfigurationProperties(VaultProperties.class)
public class SecretCipherConfig implements DisposableBean {

  private static final Set<String> LOCAL_FORBIDDEN_PROFILES = Set.of("prod", "staging");

  /** Finite, so a blackholed Vault fails the probe instead of hanging the request. */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

  /** Renews the session token; created only for the Vault cipher. */
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
    if (props.uri() == null || props.roleId() == null) {
      throw new IllegalStateException(
          "emitkit.vault.uri and emitkit.vault.role-id are required when emitkit.vault.cipher=vault");
    }
    VaultEndpoint endpoint = VaultEndpoint.from(props.uri());
    SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
    requests.setConnectTimeout(CONNECT_TIMEOUT);
    requests.setReadTimeout(READ_TIMEOUT);
    RestClientBuilder clientBuilder =
        RestClientBuilder.builder().endpoint(endpoint).requestFactory(requests);
    RestClient restClient = clientBuilder.build();
    AppRoleAuthenticationOptions options =
        AppRoleAuthenticationOptions.builder()
            .roleId(AppRoleAuthenticationOptions.RoleId.provided(props.roleId()))
            .secretId(AppRoleAuthenticationOptions.SecretId.provided(props.secretId()))
            .build();

    // SimpleSessionManager would keep the first token forever; this one renews it.
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
    if (Arrays.stream(environment.getActiveProfiles())
        .anyMatch(LOCAL_FORBIDDEN_PROFILES::contains)) {
      throw new IllegalStateException("LocalAesCipher is not allowed with profile prod/staging");
    }
    return new LocalAesCipher();
  }
}
