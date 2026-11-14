package dev.emitkit.worker.pipeline;

import dev.emitkit.domain.StepName;

/** Writes the log lines of a run, which users read while the run is in progress. */
public interface RunLogger {

  /** Drops every line. The runner uses it until the Redis-backed logger exists. */
  RunLogger NOOP =
      new RunLogger() {
        @Override
        public void info(String m) {}

        @Override
        public void warn(String m) {}

        @Override
        public void error(String m) {}

        @Override
        public void step(StepName s) {}

        @Override
        public void flush() {}

        @Override
        public void done() {}
      };

  /** Writes an informational line. */
  void info(String m);

  /** Writes a warning line. */
  void warn(String m);

  /** Writes an error line. */
  void error(String m);

  /** Marks the start of a step, so the following lines belong to it. */
  void step(StepName s);

  /** Pushes buffered lines out. */
  void flush();

  /** Marks the end of the run's log. */
  void done();
}
