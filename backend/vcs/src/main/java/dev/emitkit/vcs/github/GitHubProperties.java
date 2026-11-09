package dev.emitkit.vcs.github;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * GitHub client settings, bound from {@code emitkit.github.*}.
 *
 * @param apiUrl the REST API root
 * @param cacheDir the directory of the HTTP response cache, or null to run without a cache
 * @param cacheSizeMb the maximum size of the response cache in megabytes
 */
@ConfigurationProperties("emitkit.github")
public record GitHubProperties(
    @DefaultValue("https://api.github.com") String apiUrl,
    Path cacheDir,
    @DefaultValue("256") long cacheSizeMb) {}
