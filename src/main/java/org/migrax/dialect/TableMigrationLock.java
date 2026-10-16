package org.migrax.dialect;

import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * A migration lock for databases without session locks (CockroachDB, YugabyteDB before
 * 2025.1): a row in {@code migrax_lock}. Inserting it fails at once when another process holds
 * it, and it is deleted when the migration ends. A process that was killed leaves the row
 * behind; the error message says how to remove it.
 */
final class TableMigrationLock {
  private TableMigrationLock() {}

  /** The lock on databases with PostgreSQL's SQL (CockroachDB, YugabyteDB). */
  static AutoCloseable acquire(Connection connection, String resource)
      throws SQLException {
    return acquire(connection, resource, "timestamptz");
  }

  /**
   * @param timestampType the SQL type of {@code locked_at}
   */
  static AutoCloseable acquire(Connection connection, String resource, String timestampType)
      throws SQLException {
    return acquire(connection, resource, timestampType, true);
  }

  /**
   * @param ifNotExists whether the database has {@code CREATE TABLE IF NOT EXISTS}; without
   *     it, a table that is already there (SQLState X0Y32 on Derby) is fine
   */
  static AutoCloseable acquire(Connection connection, String resource, String timestampType,
                               boolean ifNotExists) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "")
          + "migrax_lock (lock_name varchar(64) NOT NULL PRIMARY KEY, locked_by varchar(255), "
          + "locked_at " + timestampType + " NOT NULL)");
    } catch (SQLException e) {
      if (ifNotExists || !"X0Y32".equals(e.getSQLState())) {
        throw e;
      }
      if (!autoCommit) {
        connection.rollback();
      }
    }
    commitIfManual(connection, autoCommit);
    int inserted;
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO migrax_lock "
        + "(lock_name, locked_by, locked_at) VALUES (?, ?, CURRENT_TIMESTAMP)")) {
      insert.setString(1, resource);
      insert.setString(2, owner());
      inserted = insert.executeUpdate();
    } catch (SQLException e) {
      // The row is there: another process holds the lock.
      if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) {
        throw e;
      }
      if (!autoCommit) {
        connection.rollback();
      }
      inserted = 0;
    }
    commitIfManual(connection, autoCommit);
    if (inserted == 0) {
      String holder = "";
      try (PreparedStatement query = connection.prepareStatement(
          "SELECT locked_by, locked_at FROM migrax_lock WHERE lock_name = ?")) {
        query.setString(1, resource);
        try (ResultSet result = query.executeQuery()) {
          if (result.next()) {
            holder = " (" + result.getString(1) + ", since " + result.getString(2) + ")";
          }
        }
      }
      commitIfManual(connection, autoCommit);
      throw new MigrationLockHeldException("Another Migrax process is applying migrations" + holder + ". If "
          + "none is running, a killed process left its lock: remove it with DELETE FROM "
          + "migrax_lock WHERE lock_name = '" + resource + "'.");
    }
    return () -> {
      // Work that ends here has committed or failed; never commit a failed migration's work
      // together with the lock release.
      if (!connection.getAutoCommit()) {
        connection.rollback();
      }
      try (PreparedStatement delete =
               connection.prepareStatement("DELETE FROM migrax_lock WHERE lock_name = ?")) {
        delete.setString(1, resource);
        delete.executeUpdate();
      }
      commitIfManual(connection, connection.getAutoCommit());
    };
  }

  private static void commitIfManual(Connection connection, boolean autoCommit)
      throws SQLException {
    if (!autoCommit) {
      connection.commit();
    }
  }

  private static String owner() {
    String host;
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (Exception e) {
      host = "unknown host";
    }
    return "process " + ProcessHandle.current().pid() + " on " + host;
  }
}
