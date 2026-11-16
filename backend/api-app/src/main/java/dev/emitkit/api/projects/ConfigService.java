package dev.emitkit.api.projects;

import dev.emitkit.api.access.OrgAccessService;
import dev.emitkit.api.access.Permission;
import dev.emitkit.api.access.ProjectAccess;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads and saves the generation settings of a project. Saving appends a version: the new row takes
 * the next version of the latest one, in the same transaction, and the unique (project, version)
 * key makes a concurrent save fail with CONFLICT instead of writing a duplicate.
 *
 * <p>The API key is encrypted with the row's id as its context, so each row's ciphertext is bound
 * to that row. A save that leaves the key unchanged re-encrypts the stored key for the new row.
 */
@Service
public class ConfigService {

  private static final String SECRET_TABLE = "project_configs";
  private static final String SECRET_COLUMN = "ai_api_key_enc";

  /**
   * SQLSTATE of a unique violation, and the default name Postgres gives UNIQUE(project_id,
   * version).
   */
  private static final String UNIQUE_VIOLATION = "23505";

  private static final String VERSION_KEY = "project_configs_project_id_version_key";

  private final ProjectAccess projectAccess;
  private final ProjectConfigRepository configs;
  private final SecretCipher cipher;
  private final TransactionTemplate transactions;

  public ConfigService(
      ProjectAccess projectAccess,
      ProjectConfigRepository configs,
      SecretCipher cipher,
      PlatformTransactionManager transactionManager) {
    this.projectAccess = projectAccess;
    this.configs = configs;
    this.cipher = cipher;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Returns the latest config version of a project. The API key is never returned; {@code aiKeySet}
   * says whether one is stored.
   *
   * @throws AppException NOT_FOUND when the project is missing or has no config, FORBIDDEN when the
   *     signed-in user's role lacks {@code PROJECT_VIEW}
   */
  public ConfigResponse get(String projectId) {
    projectAccess.require(projectId, Permission.PROJECT_VIEW);
    ProjectConfig latest =
        configs
            .findLatest(projectId)
            .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "config not found"));
    return toResponse(latest);
  }

  /**
   * Saves a new config version for a project and returns it.
   *
   * @throws AppException NOT_FOUND or FORBIDDEN from the access check; VALIDATION_FAILED listing
   *     every invalid field; CONFLICT when a concurrent save took the same version
   */
  public ConfigResponse save(String projectId, ConfigRequest request) {
    projectAccess.require(projectId, Permission.CONFIG_EDIT);
    String userId = OrgAccessService.currentUserId();
    ConfigValidator.check(request);
    try {
      return transactions.execute(status -> append(projectId, request, userId));
    } catch (DataIntegrityViolationException e) {
      if (!isVersionClash(e)) {
        throw e;
      }
      throw new AppException(
          ErrorCode.CONFLICT,
          "The config was saved by another request; reload and try again.",
          Map.of(),
          e);
    }
  }

  /**
   * True when the failure is the unique (project_id, version) key, which a concurrent save hits.
   * Any other integrity failure is a different problem and is not reported as a conflict.
   */
  static boolean isVersionClash(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof SQLException sql
          && UNIQUE_VIOLATION.equals(sql.getSQLState())
          && sql.getMessage() != null
          && sql.getMessage().contains(VERSION_KEY)) {
        return true;
      }
    }
    return false;
  }

  /** Inserts the next version. Runs inside the transaction opened by {@link #save}. */
  private ConfigResponse append(String projectId, ConfigRequest request, String userId) {
    ProjectConfig previous = configs.findLatest(projectId).orElse(null);
    String id = Ids.newUlid();
    int version = previous == null ? 1 : previous.getVersion() + 1;
    var config = new ProjectConfig(id, projectId, version, request.versionStrategy(), userId);
    config.setOutputs(new ArrayList<>(new LinkedHashSet<>(request.outputs())));
    List<String> languages = request.sdkLanguages() == null ? List.of() : request.sdkLanguages();
    config.setSdkLanguages(new ArrayList<>(new LinkedHashSet<>(languages)));
    config.setOutputDir(request.outputDir());
    config.setNpmScope(nullIfBlank(request.npmScope()));
    config.setPypiName(nullIfBlank(request.pypiName()));
    config.setMavenGroupId(nullIfBlank(request.mavenGroupId()));
    config.setMavenArtifactId(nullIfBlank(request.mavenArtifactId()));
    config.setBreakingGate(request.breakingGate());
    config.setLintFailOn(request.lintFailOn());
    String provider = request.aiProvider();
    config.setAiProvider(
        provider == null || provider.isBlank() ? ConfigValidator.DEFAULT_PROVIDER : provider);
    config.setAiModel(nullIfBlank(request.aiModel()));
    config.setAiApiKeyEnc(apiKeyEnc(request.aiApiKey(), previous, id));
    configs.saveAndFlush(config);
    return toResponse(config);
  }

  /**
   * The ciphertext of the new row's key. A null key keeps the previous row's key, re-encrypted for
   * the new row; an empty key clears it; any other value is a new key.
   */
  private @Nullable String apiKeyEnc(
      @Nullable String submitted, @Nullable ProjectConfig previous, String newId) {
    if (submitted == null) {
      if (previous == null || previous.getAiApiKeyEnc() == null) {
        return null;
      }
      String key = cipher.decrypt(previous.getAiApiKeyEnc(), keyContext(previous.getId()));
      return cipher.encrypt(key, keyContext(newId));
    }
    if (submitted.isEmpty()) {
      return null;
    }
    return cipher.encrypt(submitted, keyContext(newId));
  }

  private static SecretContext keyContext(String configId) {
    return new SecretContext(SECRET_TABLE, SECRET_COLUMN, configId);
  }

  private static @Nullable String nullIfBlank(@Nullable String value) {
    return value == null || value.isBlank() ? null : value;
  }

  private static ConfigResponse toResponse(ProjectConfig config) {
    return new ConfigResponse(
        config.getVersion(),
        config.getOutputs(),
        config.getSdkLanguages(),
        config.getOutputDir(),
        config.getNpmScope(),
        config.getPypiName(),
        config.getMavenGroupId(),
        config.getMavenArtifactId(),
        config.getVersionStrategy(),
        config.getBreakingGate(),
        config.getLintFailOn(),
        config.getAiProvider(),
        config.getAiModel(),
        config.getAiApiKeyEnc() != null);
  }
}
