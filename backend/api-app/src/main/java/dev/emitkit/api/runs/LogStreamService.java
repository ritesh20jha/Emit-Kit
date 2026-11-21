package dev.emitkit.api.runs;

import dev.emitkit.domain.GenerationRun;
import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import dev.emitkit.domain.GenerationRunRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Streams a run's log lines. A stream subscribes to the run's channel, replays the stored lines
 * after the client's last seq, then sends the live lines in seq order, never twice. The worker
 * publishes a line before it saves it, so a live line can run ahead of the database: a seq gap is
 * filled from the database for a bounded wait. Each stream works on its own serial queue, so a slow
 * client stalls only its own stream.
 */
@Service
public class LogStreamService {

  private static final Logger LOG = LoggerFactory.getLogger(LogStreamService.class);

  /** The most lines a page returns: one GET response, and one replay page. */
  static final int MAX_LINES = 5_000;

  static final Duration TIMEOUT = Duration.ofMinutes(30);

  private final GenerationRunLogRepository logs;
  private final GenerationRunRepository runs;
  private final RedisLogSubscriber subscriber;
  private final Duration heartbeat;
  private final Duration gapPoll;
  private final Duration gapTimeout;
  private final Duration grace;
  private final ScheduledExecutorService timers =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().daemon().name("run-log-timer").factory());

  public LogStreamService(
      GenerationRunLogRepository logs,
      GenerationRunRepository runs,
      RedisLogSubscriber subscriber,
      @Value("${emitkit.logs.heartbeat:15s}") Duration heartbeat,
      @Value("${emitkit.logs.gap-poll:500ms}") Duration gapPoll,
      @Value("${emitkit.logs.gap-timeout:5s}") Duration gapTimeout,
      @Value("${emitkit.logs.terminal-grace:3s}") Duration grace) {
    this.logs = logs;
    this.runs = runs;
    this.subscriber = subscriber;
    this.heartbeat = heartbeat;
    this.gapPoll = gapPoll;
    this.gapTimeout = gapTimeout;
    this.grace = grace;
  }

  @PreDestroy
  void shutdown() {
    timers.shutdownNow();
  }

  /** Up to {@link #MAX_LINES} stored lines of a run after {@code afterSeq}, in order. */
  public List<LogLine> after(String runId, int afterSeq) {
    return logs
        .findByRunIdAndSeqGreaterThanOrderBySeq(runId, afterSeq, PageRequest.of(0, MAX_LINES))
        .stream()
        .map(LogStreamService::toLine)
        .toList();
  }

  /** A live stream of a run's lines after {@code lastEventId}. */
  public SseEmitter stream(String runId, int lastEventId) {
    return new Stream(runId, lastEventId).open();
  }

  private static LogLine toLine(GenerationRunLog line) {
    return new LogLine(
        line.getSeq(), line.getTs(), line.getLevel(), line.getStep(), line.getMessage());
  }

  /**
   * One open stream. Its state and emitter are touched only on {@link #serial}, one step at a time.
   * The Redis dispatch thread, the timers and the emitter callbacks only queue work or close.
   */
  private final class Stream {

    private final String runId;
    private final SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());

    /** One step at a time, in the order queued: a thread per stream, which ends with the stream. */
    private final ExecutorService serial =
        Executors.newSingleThreadExecutor(Thread.ofVirtual().name("run-log-stream").factory());

    /** Live lines not yet sent, by seq. A line waits until the lines before it are sent. */
    private final TreeMap<Integer, LogLine> pending = new TreeMap<>();

    private final AtomicReference<Runnable> unsubscribe = new AtomicReference<>();
    private volatile boolean finished;
    private volatile @Nullable ScheduledFuture<?> beat;

    /** The seq of the last line sent, or the client's Last-Event-ID to begin with. */
    private int lastSent;

    /** True once the run is seen as ended: done is due from the worker, or after a grace. */
    private boolean ending;

    private boolean gapPollQueued;

    /** When the current gap was first seen, in nanoTime; 0 when there is none. */
    private long gapSince;

    Stream(String runId, int lastEventId) {
      this.runId = runId;
      this.lastSent = lastEventId;
    }

    SseEmitter open() {
      emitter.onCompletion(this::close);
      emitter.onTimeout(this::close);
      emitter.onError(error -> close());
      submit(this::start);
      return emitter;
    }

    private void start() {
      try {
        // Subscribe before reading the stored lines, so no line can fall between the two.
        Runnable remover = subscriber.subscribe(runId, this::onLine, this::onDone);
        unsubscribe.set(remover);
        if (finished) {
          runRemover();
        }
        readStored();
        GenerationRun ended = endedRun();
        if (ended != null) {
          beginEnding(ended);
        }
        if (!finished) {
          ScheduledFuture<?> ticks =
              timers.scheduleAtFixedRate(
                  () -> submit(this::tick),
                  heartbeat.toMillis(),
                  heartbeat.toMillis(),
                  TimeUnit.MILLISECONDS);
          beat = ticks;
          if (finished) {
            ticks.cancel(false);
          }
        }
      } catch (RuntimeException e) {
        LOG.warn("run {}: could not start the log stream", runId, e);
        close();
        emitter.complete();
      }
    }

    /** Queues a step. Once the stream has closed, a step queued late is dropped. */
    private void submit(Runnable step) {
      try {
        serial.execute(step);
      } catch (RejectedExecutionException closed) {
        // The stream has ended, so there is nothing left to do.
      }
    }

    /** Redis dispatch thread: queues the line. */
    private void onLine(LogLine line) {
      submit(() -> {
        if (!finished && line.seq() > lastSent) {
          pending.put(line.seq(), line);
          drain();
        }
      });
    }

    /** Redis dispatch thread: queues the finish. */
    private void onDone() {
      submit(this::finishNow);
    }

    /** Sends the pending lines that are in order. A gap waits for the database, up to a bound. */
    private void drain() {
      while (!finished && !pending.isEmpty()) {
        int first = pending.firstKey();
        if (first <= lastSent) {
          pending.pollFirstEntry();
          continue;
        }
        if (first == lastSent + 1) {
          gapSince = 0;
        } else {
          if (gapSince == 0) {
            gapSince = System.nanoTime();
          }
          if (System.nanoTime() - gapSince < gapTimeout.toNanos()) {
            scheduleGapPoll();
            return;
          }
          LOG.warn(
              "run {}: log lines {}..{} were not stored, sent without them",
              runId,
              lastSent + 1,
              first - 1);
        }
        send(pending.pollFirstEntry().getValue());
      }
      gapSince = 0;
    }

    private void scheduleGapPoll() {
      if (!gapPollQueued) {
        gapPollQueued = true;
        timers.schedule(
            () ->
                submit(() -> {
                  gapPollQueued = false;
                  if (!finished) {
                    readStored();
                    drain();
                  }
                }),
            gapPoll.toMillis(),
            TimeUnit.MILLISECONDS);
      }
    }

    /** Sends the stored lines after {@code lastSent}, a page at a time, until none are left. */
    private void readStored() {
      while (!finished) {
        List<GenerationRunLog> page =
            logs.findByRunIdAndSeqGreaterThanOrderBySeq(
                runId, lastSent, PageRequest.of(0, MAX_LINES));
        for (GenerationRunLog line : page) {
          if (finished) {
            return;
          }
          send(toLine(line));
        }
        if (page.size() < MAX_LINES) {
          return;
        }
      }
    }

    private void send(LogLine line) {
      if (write(
          SseEmitter.event()
              .id(String.valueOf(line.seq()))
              .name("log")
              .data(line, MediaType.APPLICATION_JSON))) {
        lastSent = line.seq();
      }
    }

    /**
     * The run has ended, but the worker may still be saving its last lines, since it sets the
     * status first. Done goes out when the worker's done message arrives, after a grace, or at once
     * if the run ended long ago.
     */
    private void beginEnding(GenerationRun run) {
      if (ending) {
        return;
      }
      ending = true;
      Instant endedAt = run.getFinishedAt();
      if (endedAt != null && endedAt.isBefore(Instant.now().minus(grace))) {
        finishNow();
      } else {
        timers.schedule(() -> submit(this::finishNow), grace.toMillis(), TimeUnit.MILLISECONDS);
      }
    }

    /** Heartbeat, and the check for a run that ended without its done message. */
    private void tick() {
      if (!finished && write(SseEmitter.event().comment("heartbeat"))) {
        GenerationRun ended = ending ? null : endedRun();
        if (ended != null) {
          beginEnding(ended);
        }
      }
    }

    private @Nullable GenerationRun endedRun() {
      return runs.findById(runId).filter(run -> run.getStatus().isTerminal()).orElse(null);
    }

    /** Reads what the database has, sends what is still missing, then done. Ends the stream. */
    private void finishNow() {
      if (finished) {
        return;
      }
      try {
        readStored();
      } catch (RuntimeException e) {
        LOG.warn("run {}: could not read the last log lines", runId, e);
      }
      for (LogLine line : pending.values()) {
        if (finished) {
          break;
        }
        if (line.seq() > lastSent) {
          send(line);
        }
      }
      pending.clear();
      if (write(SseEmitter.event().name("done").data("[DONE]"))) {
        emitter.complete();
      }
      close();
    }

    /** Writes one event. A failed write means the client has gone, so the stream closes. */
    private boolean write(SseEventBuilder event) {
      try {
        emitter.send(event);
        return true;
      } catch (IOException | IllegalStateException e) {
        close();
        return false;
      }
    }

    /** Stops the heartbeat and removes the Redis listener. Safe from any thread, and repeatable. */
    private void close() {
      finished = true;
      serial.shutdown();
      ScheduledFuture<?> ticks = beat;
      if (ticks != null) {
        ticks.cancel(false);
      }
      runRemover();
    }

    private void runRemover() {
      Runnable remover = unsubscribe.getAndSet(null);
      if (remover != null) {
        remover.run();
      }
    }
  }
}
