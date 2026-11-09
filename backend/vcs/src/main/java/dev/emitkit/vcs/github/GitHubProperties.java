package dev.emitkit.vcs.github;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GitHub client settings, bound from {@code emitkit.github.*}. A null {@code cacheDir} disables the
 * response cache.
 */
@ConfigurationProperties("emitkit.github")
public record GitHubProperties(
    @DefaultValue("https://api.github.com") String apiUrl,
    Path cacheDir,
    @DefaultValue("256") long cacheSizeMb) {}
