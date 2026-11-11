package dev.emitkit.api.auth;

import dev.emitkit.api.config.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Publishes {@link UserLoggedInEvent} and sends the browser back to the web app. */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

  private final ApplicationEventPublisher events;
  private final WebProperties web;

  public LoginSuccessHandler(ApplicationEventPublisher events, WebProperties web) {
    this.events = events;
    this.web = web;
  }

  @Override
  public void onAuthenticationSuccess(
      HttpServletRequest request, HttpServletResponse response, Authentication authentication)
      throws IOException {
    EmitKitUser user = (EmitKitUser) authentication.getPrincipal();
    events.publishEvent(new UserLoggedInEvent(user.userId()));
    response.sendRedirect(target(null));
  }

  /** Where the browser goes after login. Until the next-path ticket it is always the web root. */
  String target(@Nullable String next) {
    return web.baseUrl() + "/";
  }
}
