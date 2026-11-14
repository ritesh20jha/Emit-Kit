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

/**
 * Creates, lists and reads generation runs. {@link #create} does not check permissions: webhooks
 * and system triggers start runs too, and they have no user. The controller checks before it calls.
 */
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

  /**
   * Queues a run and publishes its job once the transaction commits. The run uses the project's
   * latest config, or, for a rerun of a parent run, the config that parent used.
   *
   * @param userId the user who started the run, or null for a webhook or system trigger
   * @param parentRunId the run this one reruns, or null
   * @throws AppException NOT_FOUND when the project has no config, or the parent run is missing or
   *     belongs to another project; INTERNAL when the job cannot be published
   */
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

  /**
   * One page of a project's runs, newest first.
   *
   * @param status only runs with this status, or null for any
   * @param trigger only runs started by this trigger, or null for any
   * @param cursor the cursor from the previous page, or null for the first page
   * @param limit the page size, from 1 to {@link #MAX_LIMIT}; null for {@link #DEFAULT_LIMIT}
   * @throws AppException VALIDATION_FAILED for a bad limit or cursor
   */
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
    // One row past the page tells whether another page exists, so no empty last page is offered.
    List<GenerationRun> found = runs.findByProjectId(projectId, status, trigger, after, size + 1);
    if (found.size() <= size) {
      return new Page<>(found.stream().map(mapper::toSummary).toList(), null);
    }
    List<GenerationRun> page = found.subList(0, size);
    GenerationRun last = page.getLast();
    String next = RunCursor.encode(last.getCreatedAt(), last.getId());
    return new Page<>(page.stream().map(mapper::toSummary).toList(), next);
  }

  /**
   * The run with this id. The caller checks the permission on its project before it reads the run.
   *
   * @throws AppException NOT_FOUND when there is no such run
   */
  @Transactional(readOnly = true)
  public GenerationRun find(String runId) {
    return runs.findById(runId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "run not found"));
  }

  /** The full view of a run: steps, lint counts, approval and artifact availability. */
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
