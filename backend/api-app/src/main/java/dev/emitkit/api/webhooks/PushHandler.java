package dev.emitkit.api.webhooks;

import dev.emitkit.api.runs.RunService;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.RunTrigger;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Queues a webhook run when a push to the project's default branch adds, modifies or removes its
 * spec file. Every other push is ignored.
 */
@Component
class PushHandler {

  private static final List<String> CHANGE_KEYS = List.of("added", "modified", "removed");

  private final RunService runs;

  PushHandler(RunService runs) {
    this.runs = runs;
  }

  WebhookOutcome handle(Project project, WebhookEvent event) {
    JsonNode payload = event.payload();
    String defaultRef = "refs/heads/" + project.getDefaultBranch();
    if (!defaultRef.equals(payload.path("ref").asStringOpt().orElse(null))) {
      return WebhookOutcome.ignored("branch");
    }
    if (!specChanged(payload, project.getSpecPath())) {
      return WebhookOutcome.ignored("spec-not-changed");
    }
    GenerationRun run = runs.create(project.getId(), RunTrigger.WEBHOOK, false, null, null);
    return WebhookOutcome.queued(run.getId());
  }

  /** Whether any commit of the push added, modified or removed the spec file. */
  private static boolean specChanged(JsonNode payload, String specPath) {
    for (JsonNode commit : payload.path("commits")) {
      for (String key : CHANGE_KEYS) {
        for (JsonNode file : commit.path(key)) {
          if (specPath.equals(file.asStringOpt().orElse(null))) {
            return true;
          }
        }
      }
    }
    return false;
  }
}
