package dev.emitkit.worker.steps;

import dev.emitkit.ai.ChatModelFactory;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
import dev.emitkit.common.id.Ids;
import dev.emitkit.common.secret.SecretCipher;
import dev.emitkit.common.secret.SecretContext;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.Project;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.gen.core.DocsAiAssistant;
import dev.emitkit.gen.core.GeneratedFile;
import dev.emitkit.gen.core.Generator;
import dev.emitkit.gen.core.GeneratorContext;
import dev.emitkit.gen.core.GeneratorId;
import dev.emitkit.gen.core.PackageNames;
import dev.emitkit.spec.model.ApiModel;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.worker.pipeline.CancelledException;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.RunLogger;
import dev.emitkit.worker.pipeline.StepResult;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the selected generators in parallel and writes their files to the work dir (spec v1 §5.1
 * step 7). Only the docs generator uses AI, and only when the project has a key.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class RunGeneratorsStep implements PipelineStep {

  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final GeneratorRegistry registry;
  private final SpecSnapshotRepository snapshots;
  private final WorkDir workDir;
  private final SecretCipher cipher;
  private final ChatModelFactory chatModels;

  @Autowired
  public RunGeneratorsStep(
      GeneratorRegistry registry,
      SpecSnapshotRepository snapshots,
      WorkDir workDir,
      SecretCipher cipher,
      ChatModelFactory chatModels) {
    this.registry = registry;
    this.snapshots = snapshots;
    this.workDir = workDir;
    this.cipher = cipher;
    this.chatModels = chatModels;
  }

  @Override
  public StepName name() {
    return StepName.RUN_GENERATORS;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    GenerationRun run = ctx.run();
    ProjectConfig config = ctx.config();
    List<GeneratorId> selected =
        GeneratorSelection.select(config.getOutputs(), config.getSdkLanguages());
    ApiModel model = apiModel(run);
    Optional<DocsAiAssistant> ai =
        selected.contains(GeneratorId.DOCS) ? assistant(config, ctx.log()) : Optional.empty();
    GeneratorContext generatorCtx =
        new GeneratorContext(
            config.getOutputDir(),
            run.getSdkVersion(),
            names(ctx.project(), config),
            config.getSdkLanguages(),
            ai);
    List<GeneratedFile> files = generate(selected, model, generatorCtx, ctx);
    String generationId = Ids.newUlid();
    workDir.write(run.getId(), generationId, files);
    return new StepResult.Continue(new Output(files.size(), generationId));
  }

  public record Output(int fileCount, String generationId) {}

  private ApiModel apiModel(GenerationRun run) {
    SpecSnapshot snapshot =
        Optional.ofNullable(run.getSpecSnapshotId())
            .flatMap(snapshots::findById)
            .orElseThrow(() -> new AppException(ErrorCode.SPEC_NOT_FOUND, "no spec snapshot"));
    return ApiModelJson.fromJson(snapshot.getApiModel());
  }

  /**
   * The docs AI assistant for the project's key, or empty. A failure falls back to the templates
   * with a warning that names only the exception class, since its message could carry the key.
   */
  private Optional<DocsAiAssistant> assistant(ProjectConfig config, RunLogger log) {
    String encrypted = config.getAiApiKeyEnc();
    if (encrypted == null || encrypted.isBlank()) {
      return Optional.empty();
    }
    try {
      String key =
          cipher.decrypt(
              encrypted, new SecretContext("project_configs", "ai_api_key_enc", config.getId()));
      log.mask(key);
      return Optional.of(
          chatModels.docsAssistant(config.getAiProvider(), config.getAiModel(), key));
    } catch (TransientException | CancelledException e) {
      throw e;
    } catch (RuntimeException e) {
      log.warn("AI assistant unavailable, docs use templates: " + e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** The npm name falls back to the repository name, because no column stores one. */
  private static PackageNames names(Project project, ProjectConfig config) {
    String repo = project.getRepoFullName();
    return new PackageNames(
        config.getNpmScope(),
        npmName(repo.substring(repo.lastIndexOf('/') + 1)),
        config.getPypiName(),
        config.getMavenGroupId(),
        config.getMavenArtifactId());
  }

  /** Lowercases, replaces characters npm rejects, and drops a leading dot or underscore. */
  private static String npmName(String repoName) {
    String name =
        repoName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._~-]", "-").replaceAll("^[._]+", "");
    return name.isEmpty() ? "sdk" : name;
  }

  /** Runs the generators on virtual threads. A failure or a cancel stops the rest. */
  private List<GeneratedFile> generate(
      List<GeneratorId> selected, ApiModel model, GeneratorContext generatorCtx, RunContext ctx) {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Map<GeneratorId, Future<List<GeneratedFile>>> futures = new EnumMap<>(GeneratorId.class);
    try {
      for (GeneratorId id : selected) {
        Generator generator = registry.get(id);
        futures.put(id, executor.submit(() -> generator.generate(model, generatorCtx)));
      }
      return awaitAll(futures, ctx).values().stream().flatMap(List::stream).toList();
    } finally {
      // Generators may ignore interrupts, so this only stops the waiting; their output is dropped.
      futures.values().forEach(future -> future.cancel(true));
      executor.shutdownNow();
    }
  }

  /** Waits for every generator, failing as soon as one fails and polling for cancellation. */
  private Map<GeneratorId, List<GeneratedFile>> awaitAll(
      Map<GeneratorId, Future<List<GeneratedFile>>> futures, RunContext ctx) {
    Map<GeneratorId, List<GeneratedFile>> done = new EnumMap<>(GeneratorId.class);
    try {
      while (done.size() < futures.size()) {
        ctx.checkCancelled();
        futures.forEach((id, future) -> {
          if (!done.containsKey(id) && future.isDone()) {
            done.put(id, resultOf(id, future));
          }
        });
        if (done.size() < futures.size()) {
          Thread.sleep(POLL_INTERVAL);
        }
      }
    } catch (InterruptedException e) {
      // A shutdown interrupt is not a user cancel (that comes from checkCancelled): retry the run.
      Thread.currentThread().interrupt();
      throw new TransientException(ErrorCode.INTERNAL, "interrupted while generating", Map.of(), e);
    }
    return done;
  }

  /** A finished generator's files. A cancel or transient failure propagates; any other is named. */
  private static List<GeneratedFile> resultOf(GeneratorId id, Future<List<GeneratedFile>> future) {
    if (future.state() == Future.State.SUCCESS) {
      return future.resultNow();
    }
    Throwable cause = future.exceptionNow();
    if (cause instanceof CancelledException || cause instanceof TransientException) {
      throw (RuntimeException) cause;
    }
    String detail =
        cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    throw new AppException(
        ErrorCode.GENERATOR_ERROR, "generator " + id + " failed: " + detail, Map.of(), cause);
  }
}
