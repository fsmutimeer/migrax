package org.migrax.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * YugabyteDB (YSQL) dialect. YugabyteDB runs PostgreSQL's own SQL layer on distributed storage,
 * so it writes the SQL PostgreSQL does. It is reached with the PostgreSQL driver
 * ({@code jdbc:postgresql://host:5433/db}), which reports "PostgreSQL", and is recognized from
 * the server's {@code version()} ({@link #acceptsConnection}); YugabyteDB's own driver
 * ({@code jdbc:yugabytedb:}) works too.
 *
 * <p>What differs from PostgreSQL: DDL commits at once instead of with its transaction (as on
 * MySQL), and there are no large objects ({@code @Lob} with Hibernate's PostgreSQL mapping).
 *
 * @since 0.12.0
 */
public final class YugabyteDialect extends PostgresDialect {

  @Override
  public String id() {
    return "yugabytedb";
  }

  @Override
  public java.util.List<String> aliases() {
    return java.util.List.of("yugabyte", "yb");
  }

  /** YugabyteDB's own driver; with the PostgreSQL driver, {@link #acceptsConnection} decides. */
  @Override
  public boolean acceptsUrl(String jdbcUrl) {
    return jdbcUrl != null && jdbcUrl.startsWith("jdbc:yugabytedb:");
  }

  @Override
  public boolean acceptsProduct(String productName) {
    return productName.toLowerCase(Locale.ROOT).contains("yugabyte");
  }

  @Override
  public boolean acceptsConnection(Connection connection) throws SQLException {
    return isYugabyte(connection);
  }

  /** DDL commits at once, so a failed migration can leave part of its changes behind. */
  @Override
  public boolean transactionalDdl() {
    return false;
  }

  /**
   * PostgreSQL's advisory lock where YugabyteDB has it (2025.1 and newer, setting
   * {@code yb_enable_advisory_locks}), else a row in {@code migrax_lock}. Asking for the
   * setting can't fail, unlike trying the lock, which would end the open transaction.
   */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    boolean advisory;
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT current_setting('yb_enable_advisory_locks', true)")) {
      advisory = result.next() && "on".equalsIgnoreCase(result.getString(1));
    }
    return advisory ? super.acquireMigrationLock(connection, resource)
        : TableMigrationLock.acquire(connection, resource);
  }

  /** True when the server behind a PostgreSQL connection is YugabyteDB. */
  static boolean isYugabyte(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    if (product != null && product.toLowerCase(Locale.ROOT).contains("yugabyte")) {
      return true;
    }
    if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgres")) {
      return false;
    }
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version()")) {
      return result.next() && result.getString(1) != null
          && result.getString(1).contains("-YB-");
    }
  }
}
