package dev.emitkit.worker.logs;

import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.worker.pipeline.RunLogger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes one run's lines. Each line is published to {@code run-logs:{runId}} when it is written, so
 * the live stream sees it at once, and is kept in a buffer until a flush saves the buffer to {@code
 * generation_run_logs}. Flushes run on {@link #step}, {@link #flush}, {@link #done} and every two
 * seconds. All methods are synchronized: the flush thread and the step thread share the buffer.
 */
final class RedisRunLogger implements RunLogger {

  private static final String DONE_MESSAGE = "{\"type\":\"done\"}";
  private static final Duration FLUSH_INTERVAL = Duration.ofSeconds(2);

  /** Longest line kept: 8192 chars, the 8 KB per-line limit for text that is mostly ASCII. */
  private static final int MAX_LINE_CHARS = 8192;

  /** Lines held while the database is unavailable. Past this, the oldest line is dropped. */
  static final int MAX_BUFFERED_LINES = 10_000;

  private static final String MASK = "****";
  private static final Logger LOG = LoggerFactory.getLogger(RedisRunLogger.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String runId;
  private final String channel;
  private final GenerationRunLogRepository logs;
  private final StringRedisTemplate redis;
  private final int maxBufferedLines;
  private final List<String> secrets = new ArrayList<>();
  private final List<GenerationRunLog> buffer = new ArrayList<>();
  private int nextSeq;
  private StepName step;
  private ScheduledFuture<?> flushing;
  private boolean done;

  /**
   * Set by {@link #done} before its flush, so a failed flush can be finished by the periodic one.
   */
  private boolean donePending;

  /** {@code lastSeq} is the highest seq already saved for the run, or 0. */
  RedisRunLogger(
      String runId,
      int lastSeq,
      GenerationRunLogRepository logs,
      StringRedisTemplate redis,
      int maxBufferedLines) {
    this.runId = runId;
    this.channel = "run-logs:" + runId;
    this.nextSeq = lastSeq + 1;
    this.logs = logs;
    this.redis = redis;
    this.maxBufferedLines = maxBufferedLines;
  }

  /** Starts the periodic flush on {@code executor}. It stops when {@link #done} is called. */
  synchronized void startFlushing(ScheduledExecutorService executor) {
    long millis = FLUSH_INTERVAL.toMillis();
    flushing =
        executor.scheduleAtFixedRate(this::flushQuietly, millis, millis, TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void info(String message) {
    write("INFO", message);
  }

  @Override
  public synchronized void warn(String message) {
    write("WARN", message);
  }

  @Override
  public synchronized void error(String message) {
    write("ERROR", message);
  }

  @Override
  public synchronized void mask(String secret) {
    if (secret != null && !secret.isBlank()) {
      secrets.add(secret);
    }
  }

  @Override
  public synchronized void step(StepName step) {
    flush();
    this.step = step;
  }

  /**
   * Saves the buffer in one batch. If the batch is rejected because one line is bad, the lines are
   * saved one at a time, and only the rejected ones are dropped. Any other failure, such as the
   * database being down, leaves the buffer as it is and throws.
   */
  @Override
  public synchronized void flush() {
    if (buffer.isEmpty()) {
      return;
    }
    try {
      logs.saveAll(copies(buffer));
      buffer.clear();
    } catch (DataIntegrityViolationException rejected) {
      saveOneByOne();
    }
  }

  private void saveOneByOne() {
    while (!buffer.isEmpty()) {
      GenerationRunLog line = buffer.getFirst();
      try {
        logs.save(copy(line));
      } catch (DataIntegrityViolationException rejected) {
        LOG.warn(
            "run {}: dropped log line seq {}, the database rejected it ({})",
            runId,
            line.getSeq(),
            rejected.getClass().getSimpleName());
      }
      buffer.removeFirst();
    }
  }

  /**
   * Flushes, then stops the periodic flush and publishes the terminal message. If the flush fails,
   * this throws and publishes nothing. The lines stay buffered and the periodic flush keeps trying;
   * once the lines are saved it publishes the terminal message itself. Calls after that do nothing.
   */
  @Override
  public synchronized void done() {
    if (done) {
      return;
    }
    donePending = true;
    flush();
    finish();
  }

  /**
   * Stops the periodic flush and publishes the terminal message. Only after the buffer is saved.
   */
  private void finish() {
    done = true;
    donePending = false;
    if (flushing != null) {
      flushing.cancel(false);
    }
    publish(DONE_MESSAGE);
  }

  /**
   * Saves what is buffered and stops the periodic flush, without publishing the terminal message. A
   * done that a failed flush left pending is finished here. A failed save is logged, and the lines
   * left in the buffer go with the logger. Does nothing after {@link #done}.
   */
  @Override
  public synchronized void close() {
    if (done) {
      return;
    }
    if (flushing != null) {
      flushing.cancel(false);
    }
    try {
      flush();
      if (donePending) {
        finish();
      }
    } catch (RuntimeException e) {
      LOG.warn("run {}: could not save log lines on close", runId, e);
    }
  }

  private void write(String level, String message) {
    String kept = truncate(hide(Objects.requireNonNullElse(message, "").replace("\u0000", "")));
    GenerationRunLog line =
        new GenerationRunLog(runId, nextSeq++, Instant.now(), level, step, kept);
    if (buffer.size() >= maxBufferedLines) {
      GenerationRunLog dropped = buffer.removeFirst();
      LOG.warn("run {}: log buffer full, dropped line seq {}", runId, dropped.getSeq());
    }
    buffer.add(line);
    publish(
        JSON.writeValueAsString(
            new LogLineMessage(line.getSeq(), line.getTs(), level, step, kept)));
  }

  /**
   * Fresh entities for a save. A save that fails may already have given an entity its id, so the
   * buffered lines are never handed to the repository themselves.
   */
  private static List<GenerationRunLog> copies(List<GenerationRunLog> lines) {
    return lines.stream().map(RedisRunLogger::copy).toList();
  }

  private static GenerationRunLog copy(GenerationRunLog line) {
    return new GenerationRunLog(
        line.getRunId(),
        line.getSeq(),
        line.getTs(),
        line.getLevel(),
        line.getStep(),
        line.getMessage());
  }

  /** Replaces every registered secret with {@code ****}. */
  private String hide(String text) {
    String result = text;
    for (String secret : secrets) {
      result = result.replace(secret, MASK);
    }
    return result;
  }

  private static String truncate(String text) {
    return text.length() <= MAX_LINE_CHARS ? text : text.substring(0, MAX_LINE_CHARS);
  }

  /**
   * The live stream is best effort: the line is still saved by the next flush if Redis is down, so
   * a failed publish must not fail the step that wrote the line.
   */
  private void publish(String payload) {
    try {
      redis.convertAndSend(channel, payload);
    } catch (RuntimeException e) {
      // Class and message only: a stack trace per line would flood the log while Redis is down.
      LOG.warn(
          "run {}: could not publish a log line to {} ({}: {})",
          runId,
          channel,
          e.getClass().getName(),
          e.getMessage());
    }
  }

  /**
   * The periodic flush. A failed flush must not end the task, so the lines stay buffered for the
   * next try. A flush that succeeds after a failed {@link #done} finishes the run.
   */
  private synchronized void flushQuietly() {
    if (done) {
      return;
    }
    try {
      flush();
      if (donePending) {
        finish();
      }
    } catch (RuntimeException e) {
      LOG.warn("run {}: could not save log lines, will retry at the next flush", runId, e);
    }
  }
}
