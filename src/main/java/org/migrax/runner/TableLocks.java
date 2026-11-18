package org.migrax.runner;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.dialect.TableLockTimeoutSql;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

/**
 * The table lock timeout of one run on one connection: set before the work that runs in a
 * transaction, and set back to the connection's own value when the run ends, as the
 * connection may go back to an application's pool.
 */
final class TableLocks {
  private final Dialect dialect;
  private final TableLockTimeoutSql sql;
  private final Duration timeout;
  private final String own;
  private boolean changed;

  private TableLocks(Dialect dialect, TableLockTimeoutSql sql, Duration timeout, String own) {
    this.dialect = dialect;
    this.sql = sql;
    this.timeout = timeout;
    this.own = own;
  }

  /**
   * The table lock timeout for this connection, or null when there is none: zero, or a
   * database whose waiting Migrax leaves alone. Reads the connection's own value.
   */
  static TableLocks of(Connection connection, Duration timeout) throws SQLException {
    if (timeout.isZero() || timeout.isNegative()) {
      return null;
    }
    Dialect dialect = Dialects.forConnection(connection).orElse(null);
    TableLockTimeoutSql sql = dialect == null ? null : dialect.tableLockTimeout().orElse(null);
    if (sql == null) {
      return null;
    }
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(sql.read())) {
      if (!result.next()) {
        throw new SQLException("'" + sql.read() + "' returned nothing.");
      }
      return new TableLocks(dialect, sql, timeout, result.getString(1));
    }
  }

  /** Sets the limit, also after a migration changed it for itself. */
  void limit(Connection connection) throws SQLException {
    execute(connection, sql.set(timeout));
    changed = true;
  }

  /**
   * The connection's own value, for a migration outside a transaction: its statements, such as
   * {@code CREATE INDEX CONCURRENTLY}, may wait on purpose, and the part of it that ran before
   * a timeout would stay.
   */
  void lift(Connection connection) throws SQLException {
    if (changed) {
      execute(connection, sql.restore(own));
      changed = false;
    }
  }

  /** Sets the connection's own value again and commits that, after a run that succeeded. */
  void restore(Connection connection) throws SQLException {
    if (changed) {
      execute(connection, sql.restore(own));
      connection.commit();
      changed = false;
    }
  }

  /**
   * {@link #restore} after a run that failed: the open transaction is undone first, so
   * nothing half done is committed with it. Problems are added to {@code failure}.
   */
  void restoreAfter(Connection connection, Throwable failure) {
    try {
      connection.rollback();
      restore(connection);
    } catch (SQLException | RuntimeException restoreFailure) {
      failure.addSuppressed(restoreFailure);
    }
  }

  /**
   * Whether {@code failure} is a lock wait that ran out while every change of {@code script}
   * was in one transaction, so the database undid them all. Not when the script commits or
   * rolls back on its own: the statements before that may be committed already.
   */
  boolean undidAll(Migration script, Exception failure) {
    return failure instanceof SQLException sqlFailure && sql.isTimeout(sqlFailure)
        && dialect.transactionalDdl() && !script.isJava()
        && !SqlScript.hasDirective(script.sql(), "no-transaction")
        && !SqlScript.hasTransactionControl(script.sql(), dialect.backslashEscapes());
  }

  /**
   * The error for {@link #undidAll}. {@code ErrorCode} finds it by "for a lock that another
   * session holds on a table".
   */
  SQLException timedOut(String what, String command, Exception cause) {
    return new SQLException(what + " stopped waiting for a lock that another session holds on "
        + "a table (table lock timeout " + TableLockTimeout.describe(timeout) + "), so that "
        + "queries on that table don't queue behind it. Nothing was changed and it is not "
        + "recorded as failed: run '" + command + "' again when the table is less busy, or "
        + "allow a longer wait with --table-lock-timeout or migrax.table-lock-timeout (for "
        + "example 30s).", cause);
  }

  private static void execute(Connection connection, String command) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute(command);
    }
  }
}
