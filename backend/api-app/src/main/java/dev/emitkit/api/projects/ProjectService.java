package dev.emitkit.api.projects;

import dev.emitkit.api.access.Membership;
import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.api.common.Page;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.RunCursor;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.Webhook;
import dev.emitkit.vcs.error.VcsNotFoundException;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, lists, deletes and re-hooks projects. The database rows are committed before the webhook
 * is registered on the repository, so no database transaction is open during a network call. If the
 * webhook cannot be registered, or its id cannot be saved, the project is removed again.
 */
@Service
public class ProjectService {

  private static final int DEFAULT_LIMIT = 20;
  private static final int MAX_LIMIT = 100;

  private static final Logger LOG = LoggerFactory.getLogger(ProjectService.class);
  private static final String SECRET_TABLE = "projects";
  private static final String SECRET_COLUMN = "webhook_secret_enc";

  /** Runs that have not finished. A deleted project's runs in these states are stopped. */
  private static final List<RunStatus> IN_FLIGHT =
      List.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

  private final OrgAccessService orgAccess;
  private final VcsProvider vcsProvider;
  private final ProjectRepository projects;
  private final ProjectConfigRepository configs;
  private final GenerationRunRepository runs;
  private final ProjectListQuery listing;
  private final SecretCipher cipher;
  private final WebhookRegistrar webhooks;
  private final TransactionTemplate transactions;

  public ProjectService(
      OrgAccessService orgAccess,
      VcsProvider vcsProvider,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      GenerationRunRepository runs,
      ProjectListQuery listing,
      SecretCipher cipher,
      WebhookRegistrar webhooks,
      PlatformTransactionManager transactionManager) {
    this.orgAccess = orgAccess;
    this.vcsProvider = vcsProvider;
    this.projects = projects;
    this.configs = configs;
    this.runs = runs;
    this.listing = listing;
    this.cipher = cipher;
    this.webhooks = webhooks;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * One page of an organization's projects, newest first, with each project's last run.
   *
   * @param q a text the repository name must contain, ignoring case; null or blank is no filter
   * @param status only projects whose last run has this status; null is no filter
   * @param cursor the token of the previous page, or null for the first page
   * @param limit the page size, from 1 to 100; null for the default
   * @throws AppException FORBIDDEN or NOT_FOUND from the access check; VALIDATION_FAILED for a bad
   *     limit or a malformed cursor
   */
  @Transactional(readOnly = true)
  public Page<ProjectSummary> list(
      String orgId,
      @Nullable String q,
      @Nullable RunStatus status,
      @Nullable String cursor,
      @Nullable Integer limit) {
    orgAccess.require(orgId, Permission.PROJECT_VIEW);
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new AppException(
          ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
    }
    RunCursor after = cursor == null ? null : decodeCursor(cursor);
    String filter = q == null || q.isBlank() ? null : q;
    // One extra row tells whether another page exists.
    List<ProjectListQuery.Row> found = listing.page(orgId, filter, status, after, size + 1);
    boolean more = found.size() > size;
    List<ProjectListQuery.Row> page = more ? found.subList(0, size) : found;
    String next = null;
    if (more) {
      ProjectListQuery.Row last = page.getLast();
      next = RunCursor.encode(last.createdAt(), last.summary().id());
    }
    return new Page<>(page.stream().map(ProjectListQuery.Row::summary).toList(), next);
  }

  /**
   * Creates a project for a repository that already exists on the VCS host, with its first config
   * version and a registered push webhook.
   *
   * @throws AppException FORBIDDEN or NOT_FOUND from the access check; CONFLICT when the org
   *     already has a project for the repository; VALIDATION_FAILED for a separate output
   *     repository that is missing or not visible; SPEC_NOT_FOUND when the spec file is not on the
   *     default branch; the VCS error when the webhook cannot be registered
   */
  public Project createExisting(String orgId, CreateProjectRequest request) {
    Membership member = orgAccess.require(orgId, Permission.PROJECT_CREATE);
    String userId = member.userId();
    String repo = request.repo();
    if (projects.existsByOrgIdAndRepoFullName(orgId, repo)) {
      throw new AppException(ErrorCode.CONFLICT, "Project already exists for this repo");
    }
    VcsClient vcs = vcsProvider.forProject(userId);
    String outputRepo = checkedOutputRepo(vcs, request);
    requireSpec(vcs, repo, request.specPath(), request.defaultBranch());

    // The secret is encrypted before anything is written, so a failure here leaves nothing behind.
    String projectId = Ids.newUlid();
    String secret = webhooks.newSecret();
    String secretEnc =
        cipher.encrypt(secret, new SecretContext(SECRET_TABLE, SECRET_COLUMN, projectId));
    insertProject(projectId, orgId, request, outputRepo, userId);

    Webhook hook;
    try {
      hook = webhooks.register(vcs, repo, secret);
    } catch (RuntimeException e) {
      deleteProject(projectId);
      throw e;
    }
    try {
      return attachWebhook(projectId, hook.id(), secretEnc);
    } catch (RuntimeException e) {
      removeWebhookQuietly(vcs, repo, hook.id(), e);
      deleteProject(projectId);
      throw e;
    }
  }

  /**
   * Deletes a project by marking it deleted. Its runs are kept. The push webhook is removed first,
   * on a best-effort basis: a failure is logged as a warning and the project is deleted anyway.
   * Callers check the permission.
   */
  public void softDelete(Project project) {
    Long webhookId = project.getWebhookId();
    if (webhookId != null) {
      try {
        vcsProvider
            .forProject(project.getCreatedBy())
            .deleteWebhook(project.getRepoFullName(), webhookId);
      } catch (RuntimeException e) {
        LOG.warn(
            "could not remove webhook {} of project {} on {}; deleting the project anyway",
            webhookId,
            project.getId(),
            project.getRepoFullName(),
            e);
      }
    }
    Instant now = Instant.now();
    transactions.executeWithoutResult(status -> {
      projects.findById(project.getId()).ifPresent(p -> p.markDeleted(now));
      cancelInFlightRuns(project.getId(), now);
    });
  }

  /**
   * A deleted project's runs can no longer be reached by the worker, so the ones that have not
   * finished are stopped here: queued and awaiting-approval runs are cancelled, and running ones
   * are asked to stop.
   */
  private void cancelInFlightRuns(String projectId, Instant now) {
    for (GenerationRun run : runs.findByProjectIdAndStatusIn(projectId, IN_FLIGHT)) {
      if (run.getStatus() == RunStatus.RUNNING) {
        run.setCancelRequested(true);
      } else {
        run.setStatus(RunStatus.CANCELLED);
        run.setFinishedAt(now);
      }
    }
  }

  /**
   * Replaces the project's push webhook with one that has a new secret. The old webhook is removed
   * first, and a webhook that is already gone is not an error. Callers check the permission.
   *
   * @throws AppException the VCS error when the old webhook cannot be removed or the new one cannot
   *     be registered
   */
  public WebhookStatus reRegisterWebhook(Project project) {
    String projectId = project.getId();
    String repo = project.getRepoFullName();
    VcsClient vcs = vcsProvider.forProject(project.getCreatedBy());
    // Encrypted before anything changes on the VCS host, so an encryption failure changes nothing.
    String secret = webhooks.newSecret();
    String secretEnc =
        cipher.encrypt(secret, new SecretContext(SECRET_TABLE, SECRET_COLUMN, projectId));
    Long oldWebhookId = project.getWebhookId();
    if (oldWebhookId != null) {
      removeOldWebhook(vcs, repo, oldWebhookId);
    }
    Webhook hook;
    try {
      hook = webhooks.register(vcs, repo, secret);
    } catch (RuntimeException e) {
      // The old hook is already gone on the VCS host, so the stored id would point at nothing.
      if (oldWebhookId != null) {
        clearWebhookQuietly(projectId, oldWebhookId, e);
      }
      throw e;
    }
    try {
      attachWebhook(projectId, hook.id(), secretEnc);
    } catch (RuntimeException e) {
      removeWebhookQuietly(vcs, repo, hook.id(), e);
      throw e;
    }
    return new WebhookStatus(true, hook.id());
  }

  /** The separate output repository, checked to exist; null when output stays in the repo. */
  private static @Nullable String checkedOutputRepo(VcsClient vcs, CreateProjectRequest request) {
    if (request.outputMode() != OutputMode.SEPARATE) {
      return null;
    }
    String name = request.outputRepo();
    if (name == null || name.isBlank()) {
      throw invalid("outputRepo is required when outputMode is SEPARATE");
    }
    boolean visible = vcs.listRepos().stream().anyMatch(repo -> repo.fullName().equals(name));
    if (!visible) {
      throw invalid("outputRepo " + name + " was not found");
    }
    return name;
  }

  private static void requireSpec(VcsClient vcs, String repo, String path, String ref) {
    try {
      vcs.getFile(repo, path, ref);
    } catch (VcsNotFoundException e) {
      throw new AppException(ErrorCode.SPEC_NOT_FOUND, "spec file not found: " + path);
    }
  }

  private void insertProject(
      String projectId,
      String orgId,
      CreateProjectRequest request,
      @Nullable String outputRepo,
      String userId) {
    transactions.executeWithoutResult(status -> {
      var project =
          new Project(
              projectId,
              orgId,
              request.repo(),
              request.specPath(),
              request.defaultBranch(),
              request.outputMode(),
              userId);
      project.setOutputRepoFullName(outputRepo);
      projects.save(project);
      configs.save(DefaultConfig.version1(projectId, userId));
    });
  }

  /**
   * Forgets the webhook {@code deadWebhookId}, which is no longer on the VCS host, so the project
   * does not report it as registered. Only that id is cleared: a concurrent re-register may have
   * stored a live webhook since, and that one stays. Best effort: a failed clear is logged and
   * added to {@code failure}, which is the error the caller reports.
   */
  private void clearWebhookQuietly(String projectId, long deadWebhookId, RuntimeException failure) {
    try {
      transactions.executeWithoutResult(
          status -> projects.clearWebhookIfStill(projectId, deadWebhookId));
    } catch (RuntimeException cleanup) {
      LOG.warn("could not clear the dead webhook of project {}", projectId, cleanup);
      failure.addSuppressed(cleanup);
    }
  }

  private Project attachWebhook(String projectId, long webhookId, String secretEnc) {
    return transactions.execute(status -> {
      Project project =
          projects
              .findById(projectId)
              .orElseThrow(() -> new IllegalStateException("project vanished: " + projectId));
      project.setWebhookId(webhookId);
      project.setWebhookSecretEnc(secretEnc);
      return project;
    });
  }

  /** A hook that GitHub no longer has is the state we want, so its absence is not an error. */
  private static void removeOldWebhook(VcsClient vcs, String repo, long webhookId) {
    try {
      vcs.deleteWebhook(repo, webhookId);
    } catch (VcsNotFoundException alreadyGone) {
      LOG.debug("webhook {} on {} was already gone", webhookId, repo);
    }
  }

  private void deleteProject(String projectId) {
    transactions.executeWithoutResult(status -> {
      configs.deleteByProjectId(projectId);
      projects.deleteById(projectId);
    });
  }

  /**
   * Best effort: the failure being reported is the save, so a failed removal is only added to it.
   */
  private static void removeWebhookQuietly(
      VcsClient vcs, String repo, long webhookId, RuntimeException failure) {
    try {
      vcs.deleteWebhook(repo, webhookId);
    } catch (RuntimeException cleanup) {
      LOG.warn("could not remove webhook {} on {} after a failed save", webhookId, repo, cleanup);
      failure.addSuppressed(cleanup);
    }
  }

  private static AppException invalid(String detail) {
    return new AppException(ErrorCode.VALIDATION_FAILED, detail);
  }

  private static RunCursor decodeCursor(String cursor) {
    try {
      return RunCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw invalid("cursor is malformed");
    }
  }
}
