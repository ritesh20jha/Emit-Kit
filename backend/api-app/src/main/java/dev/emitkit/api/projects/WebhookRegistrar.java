package dev.emitkit.api.projects;

import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.Webhook;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Makes the secret of a project's push webhook and registers the webhook on its repository. The
 * callback address is {@code emitkit.public-url} followed by {@code /webhooks/github}.
 */
@Component
public class WebhookRegistrar {

  /** Pushes start generation runs; merged pull requests are tagged as releases. */
  static final Set<String> EVENTS = Set.of("push", "pull_request");

  private static final String CALLBACK_PATH = "/webhooks/github";
  private static final int SECRET_BYTES = 32;

  private final String callbackUrl;
  private final SecureRandom random;

  public WebhookRegistrar(@Value("${emitkit.public-url}") String publicUrl, SecureRandom random) {
    this.callbackUrl = publicUrl.replaceAll("/+$", "") + CALLBACK_PATH;
    this.random = random;
  }

  /** A new webhook secret: 32 random bytes, hex-encoded. */
  public String newSecret() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  /** Registers the webhook on {@code repo}, signed with {@code secret}. */
  public Webhook register(VcsClient vcs, String repo, String secret) {
    return vcs.registerWebhook(repo, callbackUrl, secret, EVENTS);
  }
}
