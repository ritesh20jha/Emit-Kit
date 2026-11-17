package dev.emitkit.worker.logs;

import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.StepName;
import dev.emitkit.worker.pipeline.RunLogger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final String MASK = "****";
  private static final Logger LOG = LoggerFactory.getLogger(RedisRunLogger.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final String runId;
  private final String channel;
  private final GenerationRunLogRepository logs;
  private final StringRedisTemplate redis;
  private final List<String> secrets = new ArrayList<>();
  private final List<GenerationRunLog> buffer = new ArrayList<>();
  private int nextSeq;
  private StepName step;
  private ScheduledFuture<?> flushing;
  private boolean done;

  /** {@code lastSeq} is the highest seq already saved for the run, or 0. */
  RedisRunLogger(
      String runId, int lastSeq, GenerationRunLogRepository logs, StringRedisTemplate redis) {
    this.runId = runId;
    this.channel = "run-logs:" + runId;
    this.nextSeq = lastSeq + 1;
    this.logs = logs;
    this.redis = redis;
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

  @Override
  public synchronized void flush() {
    if (buffer.isEmpty()) {
      return;
    }
    logs.saveAll(buffer);
    buffer.clear();
  }

  /**
   * Flushes, stops the periodic flush, then publishes the terminal message. Later calls do nothing.
   */
  @Override
  public synchronized void done() {
    if (done) {
      return;
    }
    done = true;
    try {
      flush();
    } finally {
      if (flushing != null) {
        flushing.cancel(false);
      }
      publish(DONE_MESSAGE);
    }
  }

  private void write(String level, String message) {
    String kept = truncate(hide(message));
    GenerationRunLog line =
        new GenerationRunLog(runId, nextSeq++, Instant.now(), level, step, kept);
    buffer.add(line);
    publish(
        JSON.writeValueAsString(
            new LogLineMessage(line.getSeq(), line.getTs(), level, step, kept)));
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
      LOG.warn("run {}: could not publish a log line to {}", runId, channel, e);
    }
  }

  /** A failed flush must not end the periodic task, so the lines stay buffered for the next try. */
  private synchronized void flushQuietly() {
    try {
      flush();
    } catch (RuntimeException e) {
      LOG.warn("run {}: could not save log lines, will retry at the next flush", runId, e);
    }
  }
}
