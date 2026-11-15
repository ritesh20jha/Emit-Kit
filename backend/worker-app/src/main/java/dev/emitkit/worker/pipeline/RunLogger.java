package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.StepName;

/** Writes the log lines of a run, which users read while the run is in progress. */
public interface RunLogger {

  /** Drops every line. The runner uses it until the Redis-backed logger exists. */
  RunLogger NOOP =
      new RunLogger() {
        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {}

        @Override
        public void error(String message) {}

        @Override
        public void step(StepName step) {}

        @Override
        public void flush() {}

        @Override
        public void done() {}
      };

  /** Writes an informational line. */
  void info(String message);

  /** Writes a warning line. */
  void warn(String message);

  /** Writes an error line. */
  void error(String message);

  /** Marks the start of a step, so the following lines belong to it. */
  void step(StepName step);

  /** Pushes buffered lines out. */
  void flush();

  /** Marks the end of the run's log. */
  void done();
}
