package dev.emitkit.worker.steps;

import dev.emitkit.ai.ChatModelFactory;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.common.error.TransientException;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the generators the config selects, in parallel, and writes their files to the work dir (spec
 * v1 §5.1 step 7). Only the docs generator can use AI, and only when the project has a key.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public final class RunGeneratorsStep implements PipelineStep {

  static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private static final String PROJECT_CONFIGS = "project_configs";
  private static final String AI_KEY_COLUMN = "ai_api_key_enc";

  private final GeneratorRegistry registry;
  private final SpecSnapshotRepository snapshots;
  private final WorkDir workDir;
  private final SecretCipher cipher;
  private final ChatModelFactory chatModels;
  private final Duration pollInterval;

  @Autowired
  public RunGeneratorsStep(
      GeneratorRegistry registry,
      SpecSnapshotRepository snapshots,
      WorkDir workDir,
      SecretCipher cipher,
      ChatModelFactory chatModels) {
    this(registry, snapshots, workDir, cipher, chatModels, POLL_INTERVAL);
  }

  RunGeneratorsStep(
      GeneratorRegistry registry,
      SpecSnapshotRepository snapshots,
      WorkDir workDir,
      SecretCipher cipher,
      ChatModelFactory chatModels,
      Duration pollInterval) {
    this.registry = registry;
    this.snapshots = snapshots;
    this.workDir = workDir;
    this.cipher = cipher;
    this.chatModels = chatModels;
    this.pollInterval = pollInterval;
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
    workDir.write(run.getId(), files);
    return new StepResult.Continue(new Output(files.size()));
  }

  /** What the step saves: how many files it wrote. */
  public record Output(int fileCount) {}

  /** The parsed spec of the run's snapshot. */
  private ApiModel apiModel(GenerationRun run) {
    SpecSnapshot snapshot =
        Optional.ofNullable(run.getSpecSnapshotId())
            .flatMap(snapshots::findById)
            .orElseThrow(
                () -> new AppException(ErrorCode.SPEC_NOT_FOUND, "the run has no spec snapshot"));
    return ApiModelJson.fromJson(snapshot.getApiModel());
  }

  /**
   * The docs AI assistant for the project's key, or empty if it has none. The decrypted key is
   * masked in the run log and is not kept after the step.
   */
  private Optional<DocsAiAssistant> assistant(ProjectConfig config, RunLogger log) {
    String encrypted = config.getAiApiKeyEnc();
    if (encrypted == null || encrypted.isBlank()) {
      return Optional.empty();
    }
    try {
      String key =
          cipher.decrypt(
              encrypted, new SecretContext(PROJECT_CONFIGS, AI_KEY_COLUMN, config.getId()));
      log.mask(key);
      return Optional.of(
          chatModels.docsAssistant(config.getAiProvider(), config.getAiModel(), key));
    } catch (TransientException | CancelledException e) {
      // A secret-backend outage is retried by redelivery, and a cancel must stop the run.
      throw e;
    } catch (RuntimeException e) {
      // Spec 6.1: AI failures fall back to the template output. Only the class name is logged,
      // because a message could carry key material.
      log.warn("AI assistant unavailable, docs use templates: " + e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  /** The npm name falls back to the repository name, because no column stores one. */
  private static PackageNames names(Project project, ProjectConfig config) {
    String repo = project.getRepoFullName();
    return new PackageNames(
        config.getNpmScope(),
        repo.substring(repo.lastIndexOf('/') + 1),
        config.getPypiName(),
        config.getMavenGroupId(),
        config.getMavenArtifactId());
  }

  /**
   * Runs the selected generators on virtual threads and returns their files in {@link GeneratorId}
   * order. Polls for cancellation while waiting. A failure or a cancel stops the rest.
   */
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
      // Generators are pure code that may ignore interrupts, so this stops only what is waiting on
      // a result. A generator still running finishes on its own thread and its output is dropped.
      futures.values().forEach(future -> future.cancel(true));
      executor.shutdownNow();
    }
  }

  /** Waits until every generator is done, failing as soon as one fails. */
  private Map<GeneratorId, List<GeneratedFile>> awaitAll(
      Map<GeneratorId, Future<List<GeneratedFile>>> futures, RunContext ctx) {
    Map<GeneratorId, List<GeneratedFile>> done = new EnumMap<>(GeneratorId.class);
    try {
      while (done.size() < futures.size()) {
        ctx.checkCancelled();
        for (Map.Entry<GeneratorId, Future<List<GeneratedFile>>> entry : futures.entrySet()) {
          if (!done.containsKey(entry.getKey()) && entry.getValue().isDone()) {
            done.put(entry.getKey(), resultOf(entry.getKey(), entry.getValue()));
          }
        }
        if (done.size() < futures.size()) {
          Thread.sleep(pollInterval);
        }
      }
    } catch (InterruptedException e) {
      // An interrupted step stops like a cancelled one: the run stays RUNNING for redelivery.
      Thread.currentThread().interrupt();
      throw new CancelledException(ctx.run().getId());
    }
    return done;
  }

  /**
   * The files of a finished generator. A cancel or a transient failure propagates unchanged; any
   * other failure becomes {@code GENERATOR_ERROR} naming the generator.
   */
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
