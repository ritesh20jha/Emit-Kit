package dev.emitkit.api.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Three filter chains, matched in order. Actuator is stateless and open, because its management
 * port is not published. Webhooks are stateless and CSRF-free (their HMAC check lives in the hook
 * module). Everything else is the browser/API chain: session-backed, CSRF-protected, CORS
 * allowlisted, and answering failures as Problem Details.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsProperties.class)
public class SecurityConfig {

  private static final String UNAUTHENTICATED_BODY =
      "{\"type\":\"https://emitkit.dev/errors/UNAUTHENTICATED\",\"title\":\"Unauthorized\","
          + "\"status\":401,\"detail\":\"Authentication is required.\",\"code\":\"UNAUTHENTICATED\"}";
  private static final String FORBIDDEN_BODY =
      "{\"type\":\"https://emitkit.dev/errors/FORBIDDEN\",\"title\":\"Forbidden\","
          + "\"status\":403,\"detail\":\"Access denied.\",\"code\":\"FORBIDDEN\"}";

  /** Webhook intake: no session, no CSRF, open to the network (HMAC is checked in HOOK-01). */
  @Bean
  @Order(1)
  public SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/webhooks/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  /**
   * Actuator: the management port is not published, so the network is the boundary. Ordered ahead
   * of appChain, whose catch-all would otherwise answer 401 on the management port.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/actuator/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .build();
  }

  /** Browser and API traffic: CSRF cookie for SPAs, CORS allowlist, /api/v1 requires login. */
  @Bean
  @Order(2)
  public SecurityFilterChain appChain(
      HttpSecurity http, @Qualifier("corsConfigurationSource") CorsConfigurationSource cors)
      throws Exception {
    return http.cors(c -> c.configurationSource(cors))
        .csrf(csrfCustomizer())
        .addFilterAfter(csrfCookieFilter(), CsrfFilter.class)
        // No saved request: an anonymous 401 must not create a session for a retry.
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .authorizeHttpRequests(auth ->
            auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/login/**", "/oauth2/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .exceptionHandling(ex ->
            ex.authenticationEntryPoint(SecurityConfig::unauthenticated)
                .accessDeniedHandler(SecurityConfig::forbidden))
        .build();
  }

  /** Origins from emitkit.cors.allowed-origins; credentials allowed, no wildcard origins. */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(CorsProperties props) {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(props.allowedOrigins());
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
    config.setAllowedHeaders(List.of("Content-Type", "X-XSRF-TOKEN"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  private static Customizer<CsrfConfigurer<HttpSecurity>> csrfCustomizer() {
    // Plain (unmasked) token handling: the SPA echoes the XSRF-TOKEN cookie value as the header.
    return csrf ->
        csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler());
  }

  /** Touches the CSRF token on every request so the XSRF-TOKEN cookie is always issued. */
  private static Filter csrfCookieFilter() {
    return (request, response, chain) -> {
      Object token = ((HttpServletRequest) request).getAttribute(CsrfToken.class.getName());
      if (token instanceof CsrfToken csrfToken) {
        csrfToken.getToken();
      }
      chain.doFilter(request, response);
    };
  }

  private static void unauthenticated(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
      throws IOException {
    writeProblem(response, 401, UNAUTHENTICATED_BODY);
  }

  private static void forbidden(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    writeProblem(response, 403, FORBIDDEN_BODY);
  }

  private static void writeProblem(HttpServletResponse response, int status, String body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(body);
  }
}
