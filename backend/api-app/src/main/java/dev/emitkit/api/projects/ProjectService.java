package dev.emitkit.api.projects;

import dev.emitkit.api.access.Membership;
import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.OutputMode;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.ProjectRepository;
import dev.emitkit.domain.VersionStrategy;
import dev.emitkit.vcs.VcsClient;
import dev.emitkit.vcs.VcsProvider;
import dev.emitkit.vcs.Webhook;
import dev.emitkit.vcs.error.VcsNotFoundException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates projects. The database rows are committed before the webhook is registered on the
 * repository, so no database transaction is open during the network call. If the webhook cannot be
 * registered, or its id cannot be saved, the project is removed again.
 */
@Service
public class ProjectService {

  private static final Logger LOG = LoggerFactory.getLogger(ProjectService.class);
  private static final int FIRST_CONFIG_VERSION = 1;
  private static final String SECRET_TABLE = "projects";
  private static final String SECRET_COLUMN = "webhook_secret_enc";

  private final OrgAccessService orgAccess;
  private final VcsProvider vcsProvider;
  private final ProjectRepository projects;
  private final ProjectConfigRepository configs;
  private final SecretCipher cipher;
  private final WebhookRegistrar webhooks;
  private final TransactionTemplate transactions;

  public ProjectService(
      OrgAccessService orgAccess,
      VcsProvider vcsProvider,
      ProjectRepository projects,
      ProjectConfigRepository configs,
      SecretCipher cipher,
      WebhookRegistrar webhooks,
      PlatformTransactionManager transactionManager) {
    this.orgAccess = orgAccess;
    this.vcsProvider = vcsProvider;
    this.projects = projects;
    this.configs = configs;
    this.cipher = cipher;
    this.webhooks = webhooks;
    this.transactions = new TransactionTemplate(transactionManager);
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
      configs.save(
          new ProjectConfig(
              null, projectId, FIRST_CONFIG_VERSION, VersionStrategy.EMITKIT_MANAGED, userId));
    });
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
}
