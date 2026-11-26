package dev.emitkit.api.config;

import dev.emitkit.api.auth.GitHubUserService;
import dev.emitkit.api.auth.LoginSuccessHandler;
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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
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
 * Three filter chains, matched in order. Actuator and the internal probes are stateless and open,
 * because the management port is not published. Webhooks are stateless and CSRF-free (their HMAC
 * check lives in the hook module). Everything else is the browser/API chain: session-backed,
 * CSRF-protected, CORS allowlisted, and answering failures as Problem Details.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({CorsProperties.class, WebProperties.class})
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
   * Actuator and {@code /internal/**} (the restore-drill probe, served only on the management
   * port): the management port is not published, so the network is the boundary. Ordered ahead of
   * appChain, whose catch-all would otherwise answer 401 on the management port. The main port has
   * no handler for {@code /internal/**}, so it answers 404 there.
   *
   * <p>Warning: {@code /internal/**} is permitAll on BOTH ports. Register handlers under it only
   * through {@code @ManagementContextConfiguration}, never in the main context.
   */
  @Bean
  @Order(0)
  public SecurityFilterChain actuatorChain(HttpSecurity http) throws Exception {
    return http.securityMatcher("/actuator/**", "/internal/**")
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
      HttpSecurity http,
      @Qualifier("corsConfigurationSource") CorsConfigurationSource cors,
      GitHubUserService gitHubUserService,
      OAuth2AuthorizedClientRepository authorizedClientRepository,
      LoginSuccessHandler loginSuccessHandler)
      throws Exception {
    return http.cors(c -> c.configurationSource(cors))
        .csrf(csrfCustomizer())
        .addFilterAfter(csrfCookieFilter(), CsrfFilter.class)
        .addFilterBefore(rememberNext(), OAuth2AuthorizationRequestRedirectFilter.class)
        // No saved request: an anonymous 401 must not create a session for a retry.
        .requestCache(cache -> cache.requestCache(new NullRequestCache()))
        .authorizeHttpRequests(auth ->
            auth.dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/login/**", "/oauth2/**")
                .permitAll()
                .anyRequest()
                .authenticated())
        .oauth2Login(login ->
            login
                .authorizedClientRepository(authorizedClientRepository)
                .userInfoEndpoint(info -> info.userService(gitHubUserService))
                .successHandler(loginSuccessHandler))
        .logout(logout ->
            logout
                .logoutUrl("/api/v1/logout")
                .logoutSuccessHandler(SecurityConfig::noContent)
                .invalidateHttpSession(true)
                .deleteCookies("SESSION"))
        .exceptionHandling(ex ->
            ex.authenticationEntryPoint(SecurityConfig::unauthenticated)
                .accessDeniedHandler(SecurityConfig::forbidden))
        .build();
  }

  /**
   * Stores no authorized client. Boot would otherwise keep each user's plaintext GitHub token in an
   * in-memory store for the life of the process, and logout would not clear it. The token lives
   * only encrypted in oauth_accounts and is decrypted on demand by DbTokenSource, so nothing is
   * kept here. Declaring this bean also makes Boot back off its own repository.
   */
  @Bean
  OAuth2AuthorizedClientRepository authorizedClientRepository() {
    return new NoAuthorizedClientRepository();
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

  /** Keeps {@code next} from /oauth2/authorization/{id}?next=... for LoginSuccessHandler. */
  private static Filter rememberNext() {
    return (request, response, chain) -> {
      HttpServletRequest http = (HttpServletRequest) request;
      if (http.getRequestURI().startsWith("/oauth2/authorization/")) {
        // Storing null also clears a value left by an earlier attempt that had no next.
        http.getSession()
            .setAttribute(LoginSuccessHandler.NEXT_ATTRIBUTE, http.getParameter("next"));
      }
      chain.doFilter(request, response);
    };
  }

  /** Logout answers 204 and leaves the browser on the SPA, which owns the navigation. */
  private static void noContent(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
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

  /** Answers "no client" to every lookup and drops every save and removal. */
  private static final class NoAuthorizedClientRepository
      implements OAuth2AuthorizedClientRepository {

    @Override
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
        String clientRegistrationId, Authentication principal, HttpServletRequest request) {
      return null;
    }

    @Override
    public void saveAuthorizedClient(
        OAuth2AuthorizedClient authorizedClient,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}

    @Override
    public void removeAuthorizedClient(
        String clientRegistrationId,
        Authentication principal,
        HttpServletRequest request,
        HttpServletResponse response) {}
  }

  private static void writeProblem(HttpServletResponse response, int status, String body)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.getWriter().write(body);
  }
}
