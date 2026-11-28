package dev.emitkit.worker.steps;

import static dev.emitkit.domain.StepName.FETCH_SPEC;
import static dev.emitkit.domain.StepName.PARSE_SPEC;

import dev.emitkit.common.error.ErrorCode;
import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunRepository;
import dev.emitkit.domain.SpecSnapshot;
import dev.emitkit.domain.SpecSnapshotRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.spec.model.ApiModelJson;
import dev.emitkit.spec.parse.ParseResult;
import dev.emitkit.spec.parse.SpecParser;
import dev.emitkit.worker.pipeline.PipelineStep;
import dev.emitkit.worker.pipeline.RunContext;
import dev.emitkit.worker.pipeline.StepOutputs;
import dev.emitkit.worker.pipeline.StepResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parses the fetched spec into a snapshot shared by content hash (spec v1 §5.1, step 2). The run
 * then points at the snapshot, and the content is dropped from the FETCH_SPEC output.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
public class ParseSpecStep implements PipelineStep {

  /** The most parse errors reported in a failure message. */
  static final int MAX_REPORTED_ERRORS = 5;

  private static final SpecParser PARSER = new SpecParser();

  /** What PARSE_SPEC saves: the snapshot the run uses. */
  public record Output(String snapshotId) {}

  private final GenerationRunRepository runs;
  private final SpecSnapshotRepository snapshots;
  private final TransactionTemplate transactions;

  @Autowired
  public ParseSpecStep(
      GenerationRunRepository runs,
      SpecSnapshotRepository snapshots,
      PlatformTransactionManager transactionManager) {
    this.runs = runs;
    this.snapshots = snapshots;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Override
  public StepName name() {
    return PARSE_SPEC;
  }

  @Override
  public StepResult execute(RunContext ctx) {
    FetchOutput fetched =
        ctx.output(FETCH_SPEC, FetchOutput.class)
            .orElseThrow(() -> new IllegalStateException("FETCH_SPEC has no saved output"));
    GenerationRun run = ctx.run();
    if (fetched.content() == null) {
      // A crash after the snapshot was stored: the content was dropped in the same transaction
      // that set spec_snapshot_id, so the snapshot is the answer. Nothing is parsed or fetched.
      if (run.getSpecSnapshotId() == null) {
        throw new IllegalStateException(
            "FETCH_SPEC has no content and the run has no spec snapshot");
      }
      return new StepResult.Continue(new Output(run.getSpecSnapshotId()));
    }
    String content = fetched.content();
    String hash = sha256Hex(content);
    String projectId = run.getProjectId();
    // Parsed on every run, even when the hash is already stored. A stored row may predate a parser
    // or serialisation fix, and storeAndPoint repairs it from this parse.
    ParseResult parsed = PARSER.parse(content);
    if (!parsed.ok()) {
      trimFetchOutput(run.getId(), fetched.commitSha());
      return new StepResult.Fail(ErrorCode.SPEC_INVALID, firstErrors(parsed.errors()));
    }
    SpecSnapshot fresh =
        new SpecSnapshot(
            null,
            projectId,
            hash,
            content,
            ApiModelJson.toJson(parsed.model()),
            parsed.infoVersion());
    String snapshotId = save(run.getId(), projectId, hash, fresh, fetched.commitSha());
    if (snapshotId == null) {
      return new StepResult.Park();
    }
    return new StepResult.Continue(new Output(snapshotId));
  }

  /**
   * Stores the snapshot and points the run at it. When a concurrent run stores the same content
   * first, this run's insert fails on the unique key. That transaction has rolled back, and the
   * winner's has committed, so one retry finds the winner's row. A second failure propagates.
   */
  private @Nullable String save(
      String runId, String projectId, String hash, SpecSnapshot fresh, String commitSha) {
    try {
      return storeAndPoint(runId, projectId, hash, fresh, commitSha);
    } catch (DataIntegrityViolationException e) {
      return storeAndPoint(runId, projectId, hash, fresh, commitSha);
    }
  }

  /**
   * In one transaction: stores {@code fresh} unless the hash is already stored, in which case the
   * stored row is repaired to match it. Points the run at the snapshot, and replaces the FETCH_SPEC
   * output with its commit alone. Returns null, and writes nothing, if the run has become terminal.
   */
  private @Nullable String storeAndPoint(
      String runId, String projectId, String hash, SpecSnapshot fresh, String commitSha) {
    return transactions.execute(tx ->
        runs.findByIdForUpdate(runId)
            .filter(run -> !run.getStatus().isTerminal())
            .map(run -> {
              // saveAndFlush, so a unique-key violation surfaces inside this transaction.
              String id =
                  snapshots
                      .findByProjectIdAndContentHash(projectId, hash)
                      .map(stored -> repair(stored, fresh))
                      .orElseGet(() -> snapshots.saveAndFlush(fresh))
                      .getId();
              run.setSpecSnapshotId(id);
              run.setStepOutputs(
                  StepOutputs.with(
                      run.getStepOutputs(), FETCH_SPEC, Map.of("commitSha", commitSha)));
              return id;
            })
            .orElse(null));
  }

  /**
   * Brings a stored snapshot up to a fresh parse of the same content. Only the parsed columns are
   * written, and only when they differ, so a repaired row is written once and then left alone.
   */
  private SpecSnapshot repair(SpecSnapshot stored, SpecSnapshot fresh) {
    if (stored.getApiModel().equals(fresh.getApiModel())
        && stored.getInfoVersion().equals(fresh.getInfoVersion())) {
      return stored;
    }
    stored.replaceParsedFields(fresh.getApiModel(), fresh.getInfoVersion());
    return snapshots.saveAndFlush(stored);
  }

  /**
   * Replaces the FETCH_SPEC output of a run that failed to parse with its commit alone, in its own
   * transaction. A failed run is never resumed, so the spec text is not kept in the run row. Writes
   * nothing if the run has become terminal.
   */
  private void trimFetchOutput(String runId, String commitSha) {
    transactions.executeWithoutResult(tx ->
        runs.findByIdForUpdate(runId)
            .filter(run -> !run.getStatus().isTerminal())
            .ifPresent(run ->
                run.setStepOutputs(
                    StepOutputs.with(
                        run.getStepOutputs(), FETCH_SPEC, Map.of("commitSha", commitSha)))));
  }

  private static String firstErrors(List<String> errors) {
    return errors.stream().limit(MAX_REPORTED_ERRORS).collect(Collectors.joining("; "));
  }

  private static String sha256Hex(String content) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
