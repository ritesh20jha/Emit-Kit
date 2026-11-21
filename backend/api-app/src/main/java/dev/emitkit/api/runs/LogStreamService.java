package dev.emitkit.api.runs;

import dev.emitkit.domain.GenerationRunLog;
import dev.emitkit.domain.GenerationRunLogRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * Reads the stored log lines of a run and streams them live. A stream replays the stored lines
 * after the client's last seq, then forwards the lines the worker publishes, so a line is neither
 * missed nor sent twice: a line that arrives while the replay runs is buffered and sent after it.
 */
@Service
public class LogStreamService {

  /** The most lines a page returns: one GET response, and one replay page. */
  static final int MAX_LINES = 5_000;

  static final Duration TIMEOUT = Duration.ofMinutes(30);
  static final Duration HEARTBEAT = Duration.ofSeconds(15);

  private final GenerationRunLogRepository logs;
  private final RedisLogSubscriber subscriber;
  private final ScheduledExecutorService heartbeats =
      Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "run-log-heartbeat");
        thread.setDaemon(true);
        return thread;
      });

  public LogStreamService(GenerationRunLogRepository logs, RedisLogSubscriber subscriber) {
    this.logs = logs;
    this.subscriber = subscriber;
  }

  @PreDestroy
  void shutdown() {
    heartbeats.shutdownNow();
  }

  /** Up to {@link #MAX_LINES} stored lines of a run after {@code afterSeq}, in order. */
  public List<LogLine> after(String runId, int afterSeq) {
    return logs
        .findByRunIdAndSeqGreaterThanOrderBySeq(runId, afterSeq, PageRequest.of(0, MAX_LINES))
        .stream()
        .map(LogStreamService::toLine)
        .toList();
  }

  /**
   * A live stream of a run's lines after {@code lastEventId}. A {@code terminal} run has no more
   * lines to come: the stream replays what is stored, sends done and ends.
   */
  public SseEmitter stream(String runId, boolean terminal, int lastEventId) {
    return new Stream(runId, lastEventId).open(terminal);
  }

  private static LogLine toLine(GenerationRunLog line) {
    return new LogLine(
        line.getSeq(), line.getTs(), line.getLevel(), line.getStep(), line.getMessage());
  }

  /**
   * One open stream. Its state changes under its own lock: the request thread, the Redis listener
   * and the heartbeat all write to the same emitter.
   */
  private final class Stream {

    private final String runId;
    private final SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());

    /** Lines that arrived from Redis before the replay of the stored lines ended. */
    private final List<LogLine> buffered = new ArrayList<>();

    /** The seq of the last line sent, or the client's Last-Event-ID to begin with. */
    private int lastSent;

    /** False until the replay ends. Lines and done that arrive before then are held back. */
    private boolean live;

    private boolean doneSeen;
    private volatile boolean finished;
    private @Nullable Runnable unsubscribe;
    private @Nullable ScheduledFuture<?> heartbeat;

    Stream(String runId, int lastEventId) {
      this.runId = runId;
      this.lastSent = lastEventId;
    }

    SseEmitter open(boolean terminal) {
      emitter.onCompletion(this::close);
      emitter.onTimeout(this::close);
      emitter.onError(error -> close());
      // Subscribe before reading the stored lines, so no line can fall between the two.
      if (!terminal) {
        attach(subscriber.subscribe(runId, this::onLine, this::onDone));
      }
      try {
        replayStored();
        goLive(terminal);
      } catch (RuntimeException e) {
        // A failed read must not leave the Redis listener or the heartbeat behind.
        close();
        throw e;
      }
      return emitter;
    }

    /** Stores the subscription's remover, or runs it at once if the stream has already ended. */
    private synchronized void attach(Runnable remover) {
      if (finished) {
        remover.run();
      } else {
        unsubscribe = remover;
      }
    }

    private synchronized void onLine(LogLine line) {
      if (finished) {
        return;
      }
      if (live) {
        forward(line);
      } else {
        buffered.add(line);
      }
    }

    private synchronized void onDone() {
      if (finished) {
        return;
      }
      if (live) {
        finish();
      } else {
        doneSeen = true;
      }
    }

    /**
     * Sends the stored lines after {@code lastSent}, a page at a time, until none are left. Runs
     * without the lock: Redis lines that arrive meanwhile wait in the buffer.
     */
    private void replayStored() {
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

    /** Ends the replay: sends the buffered lines, then forwards the rest as they arrive. */
    private synchronized void goLive(boolean terminal) {
      live = true;
      for (LogLine line : buffered) {
        if (finished) {
          break;
        }
        forward(line);
      }
      buffered.clear();
      if (terminal || doneSeen) {
        finish();
      } else if (!finished) {
        heartbeat =
            heartbeats.scheduleAtFixedRate(
                this::beat, HEARTBEAT.toMillis(), HEARTBEAT.toMillis(), TimeUnit.MILLISECONDS);
      }
    }

    /** Sends a line unless the client has it already. */
    private void forward(LogLine line) {
      if (line.seq() > lastSent) {
        send(line);
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

    /** Sends the done event and ends the stream. The caller holds the lock. */
    private void finish() {
      if (finished) {
        return;
      }
      boolean delivered = write(SseEmitter.event().name("done").data("[DONE]"));
      close();
      if (delivered) {
        emitter.complete();
      }
    }

    private synchronized void beat() {
      if (!finished) {
        write(SseEmitter.event().comment("heartbeat"));
      }
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

    /** Stops the heartbeat and removes the Redis listener. Safe to call more than once. */
    private synchronized void close() {
      if (finished) {
        return;
      }
      finished = true;
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      if (unsubscribe != null) {
        unsubscribe.run();
        unsubscribe = null;
      }
    }
  }
}
