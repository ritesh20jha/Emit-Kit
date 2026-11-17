package dev.emitkit.worker.vcs;

import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.domain.OAuthAccountRepository;
import dev.emitkit.domain.UserRepository;
import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.github.GitHubProperties;
import dev.emitkit.vcs.github.GitHubVcsProvider;
import dev.emitkit.vcs.ratelimit.RateLimitStore;
import dev.emitkit.vcs.ratelimit.RedisRateLimitStore;
import dev.emitkit.vcs.ratelimit.Sleeper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The GitHub {@link VcsProvider} for the pipeline, with the worker's token lookup and quota store.
 * Enabled with the pipeline, since the token lookup needs the database.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(GitHubProperties.class)
public class WorkerVcsConfig {

  @Bean
  TokenSource tokenSource(
      UserRepository users, OAuthAccountRepository accounts, SecretCipher cipher) {
    return new DbTokenSource(users, accounts, cipher);
  }

  @Bean
  RateLimitStore rateLimitStore(StringRedisTemplate redis) {
    return new RedisRateLimitStore(redis);
  }

  @Bean
  VcsProvider vcsProvider(GitHubProperties props, TokenSource tokens, RateLimitStore rateLimits) {
    return new BackgroundVcsProvider(
        new GitHubVcsProvider(props, tokens, null, rateLimits, Clock.systemUTC(), Sleeper.SYSTEM));
  }
}
