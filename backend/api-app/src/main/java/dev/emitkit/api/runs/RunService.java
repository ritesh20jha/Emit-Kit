package dev.emitkit.api.runs;

import dev.emitkit.api.common.Page;
import dev.emitkit.common.error.AppException;
import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.Decision;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.ProjectConfig;
import dev.emitkit.domain.ProjectConfigRepository;
import dev.emitkit.domain.RunApproval;
import dev.emitkit.domain.RunApprovalRepository;
import dev.emitkit.domain.RunCursor;
import dev.emitkit.domain.RunStatus;
import dev.emitkit.domain.RunTrigger;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, lists, reads, cancels, reruns and decides approvals of runs. Callers check permissions;
 * webhooks start runs without a user.
 */
@Service
public class RunService {

  static final int DEFAULT_LIMIT = 20;
  static final int MAX_LIMIT = 100;

  /** The statuses a run can be cancelled from. A finished run cannot be cancelled. */
  private static final Set<RunStatus> CANCELLABLE =
      EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL);

  private final GenerationRunRepository runs;
  private final ProjectConfigRepository configs;
  private final RunApprovalRepository approvals;
  private final JobPublisher jobs;
  private final ControlPublisher controls;
  private final RunMapper mapper;

  RunService(
      GenerationRunRepository runs,
      ProjectConfigRepository configs,
      RunApprovalRepository approvals,
      JobPublisher jobs,
      ControlPublisher controls,
      RunMapper mapper) {
    this.runs = runs;
    this.configs = configs;
    this.approvals = approvals;
    this.jobs = jobs;
    this.controls = controls;
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

  /**
   * Cancels a run that has not finished. A queued or awaiting-approval run has no worker holding
   * it, so it is cancelled here; a running one stays RUNNING until its worker sees the flag and
   * stops.
   */
  @Transactional
  public GenerationRun cancel(String runId) {
    GenerationRun run = lockRun(runId);
    if (!CANCELLABLE.contains(run.getStatus())) {
      throw new AppException(
          ErrorCode.CONFLICT, "run cannot be cancelled while " + run.getStatus());
    }
    run.setCancelRequested(true);
    if (run.getStatus() != RunStatus.RUNNING) {
      run.setStatus(RunStatus.CANCELLED);
      run.setFinishedAt(Instant.now());
    }
    controls.cancelAfterCommit(runId);
    return run;
  }

  /** Starts a new run from a finished one, on the same config version and dry-run setting. */
  @Transactional
  public GenerationRun rerun(String sourceRunId, String userId) {
    GenerationRun source = find(sourceRunId);
    if (!source.getStatus().isTerminal()) {
      throw new AppException(ErrorCode.CONFLICT, "only a finished run can be rerun");
    }
    return create(
        source.getProjectId(), RunTrigger.RERUN, source.isDryRun(), userId, source.getId());
  }

  /**
   * Records a reviewer's decision on a run awaiting approval. An approval queues the run again; a
   * rejection cancels it, with the comment as the failure message.
   */
  @Transactional
  public GenerationRun decideApproval(
      String runId, String userId, Decision decision, @Nullable String comment) {
    GenerationRun run = lockRun(runId);
    if (run.getStatus() != RunStatus.AWAITING_APPROVAL) {
      throw new AppException(ErrorCode.CONFLICT, "run is not awaiting approval");
    }
    Instant now = Instant.now();
    RunApproval approval =
        approvals.findByRunId(runId).orElseGet(() -> new RunApproval(null, runId, null));
    approval.decide(userId, decision, comment, now);
    approvals.save(approval);
    if (decision == Decision.APPROVED) {
      run.setStatus(RunStatus.QUEUED);
      jobs.publishAfterCommit(runId);
    } else {
      String reason = comment == null || comment.isBlank() ? "Rejected" : "Rejected: " + comment;
      run.setStatus(RunStatus.CANCELLED);
      run.setFailure(ErrorCode.BREAKING_BLOCKED.name(), reason);
      run.setFinishedAt(now);
    }
    return run;
  }

  /**
   * The run with its row locked until the transaction ends. Cancel and approval take this lock
   * before they check the status, so a second caller waits and then sees the first one's result.
   */
  private GenerationRun lockRun(String runId) {
    return runs.findByIdForUpdate(runId)
        .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND, "run not found"));
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
