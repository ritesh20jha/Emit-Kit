package dev.emitkit.api.config;

import dev.emitkit.vcs.TokenSource;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.github.GitHubProperties;
import dev.emitkit.vcs.github.GitHubVcsProvider;
import dev.emitkit.vcs.ratelimit.RateLimitStore;
import dev.emitkit.vcs.ratelimit.RedisRateLimitStore;
import dev.emitkit.vcs.ratelimit.Sleeper;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Creates the GitHub {@link VcsProvider} that the API calls through. The provider keeps no
 * rate-limit listener: quota is recorded per user in Redis, by the interactive clients that read
 * it.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProperties.class)
public class VcsConfig {

  @Bean
  RateLimitStore rateLimitStore(StringRedisTemplate redis) {
    return new RedisRateLimitStore(redis);
  }

  @Bean
  VcsProvider vcsProvider(GitHubProperties props, TokenSource tokens, RateLimitStore rateLimits) {
    return new GitHubVcsProvider(
        props, tokens, null, rateLimits, Clock.systemUTC(), Sleeper.SYSTEM);
  }
}
