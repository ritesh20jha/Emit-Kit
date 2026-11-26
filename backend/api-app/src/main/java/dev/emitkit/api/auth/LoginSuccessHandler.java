package dev.emitkit.api.auth;

import dev.emitkit.api.config.WebProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/** Publishes {@link UserLoggedInEvent} and sends the browser back to the web app. */
@Component
public class LoginSuccessHandler implements AuthenticationSuccessHandler {

  /** Session key under which the raw {@code next} query parameter waits for the callback. */
  public static final String NEXT_ATTRIBUTE = "auth.next";

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
    HttpSession session = request.getSession(false);
    String next = null;
    if (session != null) {
      next = (String) session.getAttribute(NEXT_ATTRIBUTE);
      session.removeAttribute(NEXT_ATTRIBUTE);
    }
    response.sendRedirect(target(next));
  }

  /**
   * Where the browser goes after login: {@code next} when it is a same-origin path, else the web
   * root. Browsers treat backslashes as slashes and strip control characters, so both are refused.
   */
  String target(@Nullable String next) {
    boolean safe =
        next != null
            && next.startsWith("/")
            && !next.startsWith("//")
            && !next.contains("\\")
            && next.chars().noneMatch(Character::isISOControl);
    return web.baseUrl() + (safe ? next : "/");
  }
}
