package dev.emitkit.worker.messaging;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/**
 * Lets one worker run a generation run at a time, with a session-level PostgreSQL advisory lock on
 * a connection of its own. A worker that dies loses its session, and the broker redelivers the
 * message it had not acknowledged.
 */
@Component
@ConditionalOnProperty(prefix = "emitkit.worker.pipeline", name = "enabled", havingValue = "true")
class RunLock {

  private static final Logger LOG = LoggerFactory.getLogger(RunLock.class);
  private static final String TRY_LOCK = "select pg_try_advisory_lock(hashtext(?))";
  private static final String UNLOCK = "select pg_advisory_unlock(hashtext(?))";

  private final DataSource dataSource;

  RunLock(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** The lock of {@code runId} if no other connection holds it, otherwise empty. */
  Optional<Lease> tryAcquire(String runId) {
    Connection connection = connect();
    boolean acquired = false;
    try {
      acquired = queryBoolean(connection, TRY_LOCK, runId);
      return acquired ? Optional.of(new Lease(connection, runId)) : Optional.empty();
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("could not lock generation run " + runId, e);
    } finally {
      if (!acquired) {
        closeQuietly(connection);
      }
    }
  }

  private Connection connect() {
    try {
      return dataSource.getConnection();
    } catch (SQLException e) {
      throw new DataAccessResourceFailureException("no connection for the run lock", e);
    }
  }

  private static boolean queryBoolean(Connection connection, String sql, String runId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, runId);
      try (ResultSet rows = statement.executeQuery()) {
        rows.next();
        return rows.getBoolean(1);
      }
    }
  }

  private static void dropAllLocks(Connection connection, String runId) {
    try (Statement statement = connection.createStatement()) {
      statement.execute("select pg_advisory_unlock_all()");
    } catch (SQLException e) {
      LOG.warn("could not drop the locks of generation run {}", runId, e);
    }
  }

  private static void closeQuietly(Connection connection) {
    try {
      connection.close();
    } catch (SQLException e) {
      LOG.warn("could not return the run lock connection", e);
    }
  }

  /** A held lock. Closing it releases the lock and returns the connection to the pool. */
  static final class Lease implements AutoCloseable {

    private final Connection connection;
    private final String runId;

    private Lease(Connection connection, String runId) {
      this.connection = connection;
      this.runId = runId;
    }

    /**
     * Releases the lock. If that fails or finds no lock, all of the connection's locks are dropped,
     * so a pooled session cannot keep the run locked.
     */
    @Override
    public void close() {
      boolean released = false;
      try {
        released = queryBoolean(connection, UNLOCK, runId);
      } catch (SQLException e) {
        LOG.warn("could not release the lock of generation run {}", runId, e);
      }
      if (!released) {
        LOG.warn("lock of generation run {} was not released; dropping all locks", runId);
        dropAllLocks(connection, runId);
      }
      closeQuietly(connection);
    }
  }
}
