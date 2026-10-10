package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * CockroachDB dialect. CockroachDB speaks PostgreSQL's protocol and is reached with the
 * PostgreSQL driver ({@code jdbc:postgresql://host:26257/db}), so this dialect starts from the
 * PostgreSQL one and changes what CockroachDB does differently: integer sizes, large objects,
 * index and unique constraint removal, and the migration lock.
 *
 * <p>The driver reports the product name "PostgreSQL", so the dialect is recognized from the
 * server's {@code version()} ({@link #acceptsConnection}), or chosen with
 * {@code --dialect cockroachdb}.
 *
 * @since 0.2.0
 */
public final class CockroachDialect extends PostgresDialect {

  @Override
  public String id() {
    return "cockroachdb";
  }

  @Override
  public java.util.List<String> aliases() {
    return java.util.List.of("cockroach", "crdb");
  }

  /** The URL is the PostgreSQL driver's: {@link #acceptsConnection} decides. */
  @Override
  public boolean acceptsUrl(String jdbcUrl) {
    return false;
  }

  @Override
  public boolean acceptsProduct(String productName) {
    return productName.contains("cockroach");
  }

  @Override
  public boolean acceptsConnection(Connection connection) throws SQLException {
    return isCockroach(connection);
  }

  /** True when the server behind a PostgreSQL connection is CockroachDB. */
  static boolean isCockroach(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgres")) {
      return product != null && product.toLowerCase(Locale.ROOT).contains("cockroach");
    }
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version()")) {
      return result.next() && result.getString(1) != null
          && result.getString(1).toLowerCase(Locale.ROOT).contains("cockroachdb");
    }
  }

  /**
   * CockroachDB's {@code integer} is 64 bits by default, so 32-bit columns are written as
   * {@code int4}. It has no large objects: {@code @Lob} text is {@code text} and binary data
   * is {@code bytea}, as Hibernate maps them for CockroachDB.
   */
  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "integer" -> "int4";
      case "clob" -> "text";
      case "blob" -> "bytea";
      default -> super.logicalType(c, logical);
    };
  }

  /**
   * Hibernate's CockroachDB dialect writes {@code string} and {@code bytes}, which are
   * CockroachDB's names for {@code text} and {@code bytea}: compare them as the same type.
   */
  @Override
  public String columnType(SchemaModel.Column column) {
    String type = super.columnType(column);
    return switch (type.toLowerCase(Locale.ROOT)) {
      case "string" -> "text";
      case "bytes" -> "bytea";
      default -> type;
    };
  }

  /** Index names belong to a table in CockroachDB. */
  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(table) + "@" + q(name);
  }

  /** A unique constraint is an index in CockroachDB and is removed as one. */
  @Override
  protected String dropUnique(String table, String name) {
    return "DROP INDEX " + q(table) + "@" + q(name) + " CASCADE";
  }

  /**
   * Online changes for {@code --safe}: CockroachDB builds indexes without blocking writes
   * anyway, and a unique index is how it adds a unique constraint.
   */
  @Override
  public String renderOnline(io.migrax.ops.Operation operation) {
    if (operation instanceof io.migrax.ops.AddUnique x) {
      return "CREATE UNIQUE INDEX " + q(x.name()) + " ON " + q(x.table()) + " ("
          + q(x.column()) + ")";
    }
    return super.renderOnline(operation);
  }

  /**
   * CockroachDB has no session locks (its advisory lock functions do nothing), so the lock is a
   * row in {@code migrax_lock}: inserting it fails at once when another process holds it, and
   * it is deleted when the migration ends. A process that was killed leaves the row behind; the
   * error message says how to remove it.
   */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    try (Statement statement = connection.createStatement()) {
      statement.execute("CREATE TABLE IF NOT EXISTS migrax_lock (lock_name varchar(64) NOT NULL "
          + "PRIMARY KEY, locked_by varchar(255), locked_at timestamptz NOT NULL)");
    }
    commitIfManual(connection, autoCommit);
    int inserted;
    try (PreparedStatement insert = connection.prepareStatement("INSERT INTO migrax_lock "
        + "(lock_name, locked_by, locked_at) VALUES (?, ?, now()) ON CONFLICT DO NOTHING")) {
      insert.setString(1, resource);
      insert.setString(2, owner());
      inserted = insert.executeUpdate();
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
      throw new SQLException("Another Migrax process is applying migrations" + holder + ". If "
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
