package dev.emitkit.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Where the web app is served, bound from {@code emitkit.web.*}. Login redirects land here. */
@ConfigurationProperties("emitkit.web")
public record WebProperties(@DefaultValue("http://localhost:5173") String baseUrl) {}
