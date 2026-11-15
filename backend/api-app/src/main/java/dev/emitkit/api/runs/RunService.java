package dev.emitkit.api.runs;

import dev.emitkit.api.common.Page;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.RunCursor;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates, lists and reads runs. Callers check permissions; webhooks start runs without a user. */
@Service
public class RunService {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  private final GenerationRunRepository runs;
  private final ProjectConfigRepository configs;
  private final JobPublisher jobs;
  private final RunMapper mapper;

  RunService(
      GenerationRunRepository runs,
      ProjectConfigRepository configs,
      JobPublisher jobs,
      RunMapper mapper) {
    this.runs = runs;
    this.configs = configs;
    this.jobs = jobs;
    this.mapper = mapper;
  }

  /** Queues a run on the project's latest config, or on the parent's config for a rerun. */
  @Transactional
  public GenerationRun create(
      String projectId,
      RunTrigger trigger,
      boolean dryRun,
      @Nullable String userId,
      @Nullable String parentRunId) {
    GenerationRun run =
        new GenerationRun(
            null, projectId, configIdFor(projectId, trigger, parentRunId), trigger, dryRun, null);
    run.setTriggeredByUserId(userId);
    run.setParentRunId(parentRunId);
    runs.save(run);
    jobs.publishAfterCommit(run.getId());
    return run;
  }

  /** One page of a project's runs, newest first. A null status, trigger or limit is no filter. */
  @Transactional(readOnly = true)
  public Page<RunSummary> list(
      String projectId,
      @Nullable RunStatus status,
      @Nullable RunTrigger trigger,
      @Nullable String cursor,
      @Nullable Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw new AppException(
          ErrorCode.VALIDATION_FAILED, "limit must be between 1 and " + MAX_LIMIT);
    }
    RunCursor after = cursor == null ? null : decodeCursor(cursor);
    // One extra row tells whether another page exists.
    List<GenerationRun> found = runs.findByProjectId(projectId, status, trigger, after, size + 1);
    boolean more = found.size() > size;
    List<GenerationRun> page = more ? found.subList(0, size) : found;
    String next =
        more ? RunCursor.encode(page.getLast().getCreatedAt(), page.getLast().getId()) : null;
    return new Page<>(page.stream().map(mapper::toSummary).toList(), next);
  }

  /** The run with this id, or NOT_FOUND. The caller checks the permission on its project. */
  @Transactional(readOnly = true)
  public GenerationRun find(String runId) {
    return runs.findById(runId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "run not found"));
  }

  /** The full view of a run. */
  public RunDetail detail(GenerationRun run) {
    return mapper.toDetail(run);
  }

  private String configIdFor(String projectId, RunTrigger trigger, @Nullable String parentRunId) {
    if (trigger == RunTrigger.RERUN && parentRunId != null) {
      return runs.findById(parentRunId)
          .filter(parent -> parent.getProjectId().equals(projectId))
          .map(GenerationRun::getConfigId)
          .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "parent run not found"));
    }
    return configs
        .findLatest(projectId)
        .map(ProjectConfig::getId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "project has no config"));
  }

  private static RunCursor decodeCursor(String cursor) {
    try {
      return RunCursor.decode(cursor);
    } catch (IllegalArgumentException e) {
      throw new AppException(ErrorCode.VALIDATION_FAILED, "cursor is malformed");
    }
  }
}
