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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives GitHub deliveries at {@code /webhooks/github}. A delivery names its repository, and the
 * projects connected to that repository, as source or as separate output, are its candidates: the
 * first candidate whose webhook secret verifies the signature owns the delivery. A verified
 * delivery is de-duplicated, then answered; push and pull_request events are handed to the {@link
 * WebhookDispatcher}.
 *
 * <p>Hidden from the OpenAPI document: GitHub calls this endpoint, the SPA never does.
 */
@Hidden
@RestController
class GitHubWebhookController {

  private static final Logger LOG = LoggerFactory.getLogger(GitHubWebhookController.class);
  private static final String SECRET_COLUMN = "webhook_secret_enc";

  /** The longest request-supplied value written to the log, in code points. */
  private static final int LOG_VALUE_MAX = 100;

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
    JsonNode payload = parse(body, deliveryId);
    String repo = repoFullName(payload);
    Project project = verifiedProject(body, signature, repo, deliveryId);
    // Only a verified delivery is claimed, so a forged one cannot shadow the real redelivery.
    if (!deduper.claim(deliveryId)) {
      return WebhookOutcome.duplicate();
    }
    WebhookEvent verified = new WebhookEvent(event, deliveryId, payload);
    return switch (event) {
      case "ping" -> WebhookOutcome.pong();
      case "push", "pull_request" -> dispatch(project, verified, deliveryId);
      default -> WebhookOutcome.ignored("event");
    };
  }

  /**
   * Hands a verified push or pull_request to the dispatcher. When the dispatcher fails, the claim
   * is released first, so that GitHub's redelivery of the same delivery is not answered as a
   * duplicate of work that never happened.
   */
  private WebhookOutcome dispatch(Project project, WebhookEvent event, String deliveryId) {
    try {
      return dispatcher.handle(project, event);
    } catch (RuntimeException e) {
      deduper.release(deliveryId);
      throw e;
    }
  }

  /**
   * Parses the body. A body that is not JSON cannot name a repository, so it is unauthenticated.
   */
  private JsonNode parse(byte[] body, String deliveryId) {
    JsonNode payload;
    try {
      payload = json.readTree(body);
    } catch (JacksonException e) {
      throw unauthenticated(deliveryId, null);
    }
    if (payload == null) {
      throw unauthenticated(deliveryId, null);
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
  private Project verifiedProject(
      byte[] body, @Nullable String signature, @Nullable String repo, String deliveryId) {
    List<Project> candidates =
        repo == null
            ? List.of()
            : projects.findByRepoFullNameOrOutputRepoFullName(repo, repo).stream()
                .filter(project -> project.getWebhookSecretEnc() != null)
                .toList();
    if (candidates.isEmpty()) {
      throw unauthenticated(deliveryId, repo);
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
    throw unauthenticated(deliveryId, repo);
  }

  /**
   * Logs a rejected delivery and returns the 401 for it. The log names the repository and the
   * delivery only: never the signature, the secret or the body.
   */
  private static AppException unauthenticated(String deliveryId, @Nullable String repo) {
    LOG.warn(
        "Rejected webhook delivery {} for repository {}: signature missing or not verified",
        forLog(deliveryId),
        forLog(repo));
    return new AppException(
        ErrorCode.UNAUTHENTICATED, "The webhook signature is missing or does not verify.");
  }

  /**
   * Makes a request-supplied value safe for one log line. Control characters, separators and line
   * breaks, CR and LF among them, become '_'. A value longer than {@link #LOG_VALUE_MAX} code
   * points is cut and marked with an ellipsis. A null value is "unknown".
   */
  static String forLog(@Nullable String value) {
    if (value == null) {
      return "unknown";
    }
    String clean = value.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", "_");
    if (clean.codePointCount(0, clean.length()) <= LOG_VALUE_MAX) {
      return clean;
    }
    return clean.substring(0, clean.offsetByCodePoints(0, LOG_VALUE_MAX)) + "…";
  }
}
