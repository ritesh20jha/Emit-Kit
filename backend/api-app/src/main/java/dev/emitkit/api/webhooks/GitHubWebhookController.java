package dev.emitkit.api.webhooks;

import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.secret.CipherItem;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.vcs.WebhookVerifier;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives GitHub deliveries at {@code /webhooks/github}. A delivery names its repository, and the
 * projects connected to that repository are its candidates: the first candidate whose webhook
 * secret verifies the signature owns the delivery. A verified delivery is de-duplicated, then
 * answered; push and pull_request events are handed to the {@link WebhookDispatcher}.
 *
 * <p>Hidden from the OpenAPI document: GitHub calls this endpoint, the SPA never does.
 */
@Hidden
@RestController
class GitHubWebhookController {

  private static final String SECRET_COLUMN = "webhook_secret_enc";

  private final WebhookVerifier verifier;
  private final ProjectRepository projects;
  private final SecretCipher cipher;
  private final DeliveryDeduper deduper;
  private final WebhookDispatcher dispatcher;
  private final JsonMapper json;

  GitHubWebhookController(
      WebhookVerifier verifier,
      ProjectRepository projects,
      SecretCipher cipher,
      DeliveryDeduper deduper,
      WebhookDispatcher dispatcher,
      JsonMapper json) {
    this.verifier = verifier;
    this.projects = projects;
    this.cipher = cipher;
    this.deduper = deduper;
    this.dispatcher = dispatcher;
    this.json = json;
  }

  /**
   * Answers one delivery. The body is taken raw, because the signature covers the exact bytes
   * GitHub sent.
   *
   * @throws AppException UNAUTHENTICATED (401) when no candidate project verifies the delivery
   * @throws dev.emitkit.common.secret.SecretBackendException SECRETS_UNAVAILABLE (503) when the
   *     vault cannot decrypt the candidates' secrets, so GitHub redelivers later
   */
  @PostMapping("/webhooks/github")
  WebhookOutcome receive(
      @RequestBody byte[] body,
      @RequestHeader("X-GitHub-Event") String event,
      @RequestHeader("X-GitHub-Delivery") String deliveryId,
      @RequestHeader(value = "X-Hub-Signature-256", required = false) @Nullable String signature) {
    JsonNode payload = parse(body);
    Project project = verifiedProject(body, signature, repoFullName(payload));
    // Only a verified delivery is claimed, so a forged one cannot shadow the real redelivery.
    if (!deduper.claim(deliveryId)) {
      return WebhookOutcome.duplicate();
    }
    WebhookEvent verified = new WebhookEvent(event, deliveryId, payload);
    return switch (event) {
      case "ping" -> WebhookOutcome.pong();
      case "push", "pull_request" -> dispatcher.handle(project, verified);
      default -> WebhookOutcome.ignored("event");
    };
  }

  /**
   * Parses the body. A body that is not JSON cannot name a repository, so it is unauthenticated.
   */
  private JsonNode parse(byte[] body) {
    JsonNode payload;
    try {
      payload = json.readTree(body);
    } catch (JacksonException e) {
      throw unauthenticated();
    }
    if (payload == null) {
      throw unauthenticated();
    }
    return payload;
  }

  private static @Nullable String repoFullName(JsonNode payload) {
    return payload.path("repository").path("full_name").asStringOpt().orElse(null);
  }

  /**
   * The first project connected to {@code repo} whose webhook secret verifies the signature.
   *
   * @throws AppException UNAUTHENTICATED when the delivery names no connected repository, or no
   *     candidate verifies it
   */
  private Project verifiedProject(byte[] body, @Nullable String signature, @Nullable String repo) {
    List<Project> candidates =
        repo == null
            ? List.of()
            : projects.findByRepoFullName(repo).stream()
                .filter(project -> project.getWebhookSecretEnc() != null)
                .toList();
    if (candidates.isEmpty()) {
      throw unauthenticated();
    }
    List<CipherItem> items =
        candidates.stream()
            .map(project ->
                new CipherItem(
                    project.getWebhookSecretEnc(),
                    new SecretContext("projects", SECRET_COLUMN, project.getId())))
            .toList();
    List<String> secrets = cipher.decryptBatch(items);
    for (int i = 0; i < candidates.size(); i++) {
      if (verifier.verify(body, signature, secrets.get(i))) {
        return candidates.get(i);
      }
    }
    throw unauthenticated();
  }

  private static AppException unauthenticated() {
    return new AppException(
        ErrorCode.UNAUTHENTICATED, "The webhook signature is missing or does not verify.");
  }
}
