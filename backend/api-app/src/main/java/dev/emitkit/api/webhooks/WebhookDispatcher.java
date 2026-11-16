package dev.emitkit.api.webhooks;

import dev.emitkit.domain.Project;
import org.springframework.stereotype.Component;

/**
 * Acts on a verified push or pull_request delivery for one project. Until the push and merge
 * handlers land, every such delivery is answered as ignored.
 */
@Component
public class WebhookDispatcher {

  /** Handles {@code event} for {@code project}. */
  public WebhookOutcome handle(Project project, WebhookEvent event) {
    return WebhookOutcome.ignored("not-implemented");
  }
}
