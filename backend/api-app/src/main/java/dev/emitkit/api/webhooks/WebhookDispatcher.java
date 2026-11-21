package dev.emitkit.api.webhooks;

import dev.emitkit.domain.Project;
import org.springframework.stereotype.Component;

/**
 * Acts on a verified push or pull_request delivery for one project: a push is handed to the {@link
 * PushHandler}, a pull_request to the {@link MergeTagHandler}. Any other event is ignored.
 */
@Component
public class WebhookDispatcher {

  private final PushHandler push;
  private final MergeTagHandler mergeTag;

  WebhookDispatcher(PushHandler push, MergeTagHandler mergeTag) {
    this.push = push;
    this.mergeTag = mergeTag;
  }

  /** Handles {@code event} for {@code project}. */
  public WebhookOutcome handle(Project project, WebhookEvent event) {
    return switch (event.type()) {
      case "push" -> push.handle(project, event);
      case "pull_request" -> mergeTag.handle(project, event);
      default -> WebhookOutcome.ignored("event");
    };
  }
}
