package org.migrax.dialect;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
  public boolean buildsIndexesWithoutBlocking() {
    return true;
  }

  /** PostgreSQL's lock checks describe PostgreSQL itself, not YugabyteDB. */
  @Override
  public boolean lintsLockingDdl() {
    return false;
  }

  @Override
  public java.util.Optional<String> keepDataRestoreNote() {
    return java.util.Optional.of("-- YugabyteDB runs DDL outside the transaction, so SET NOT "
        + "NULL wouldn't see the restored\n-- values: each statement commits on its own.\n");
  }

  @Override
  public java.util.List<String> aliases() {
    return List.of("yugabyte", "yb");
  }

  @Override
  public String describeServer(String product, String version) {
    return "YugabyteDB, PostgreSQL " + version + " protocol";
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

  @Override
  public ScratchSetup scratch() {
    return new ScratchSetup("yugabytedb/yugabyte:2026.1.2.0-b137", 5433,
        "jdbc:postgresql://127.0.0.1:{port}/yugabyte", "yugabyte", "yugabyte", 240) {
      @Override
      public List<String> command() {
        return List.of("bin/yugabyted", "start", "--background=false");
      }
    };
  }

  @Override
  public SchemaCatalog schemaCatalog() {
    return new Catalog() {
      @Override
      public ResultSet importedKeys(DatabaseMetaData metadata, String catalog, String schema,
                                    String table) throws SQLException {
        return foreignKeys(metadata.getConnection(), schema, table);
      }
    };
  }

  /**
   * A table's foreign keys straight from {@code pg_constraint}, with the columns the driver's
   * {@code getImportedKeys} returns. YugabyteDB sometimes leaves keys on other than the first
   * column out of the driver's query (it joins the key arrays with {@code generate_series}),
   * while {@code pg_constraint} has them.
   */
  private static ResultSet foreignKeys(Connection connection, String schema, String table)
      throws SQLException {
    PreparedStatement statement = connection.prepareStatement("SELECT c.conname AS fk_name, "
        + "a.attname AS fkcolumn_name, ra.attname AS pkcolumn_name, r.relname AS pktable_name, "
        + "k.n AS key_seq FROM pg_catalog.pg_constraint c "
        + "JOIN pg_catalog.pg_class t ON t.oid = c.conrelid "
        + "JOIN pg_catalog.pg_namespace ns ON ns.oid = t.relnamespace "
        + "JOIN pg_catalog.pg_class r ON r.oid = c.confrelid "
        + "CROSS JOIN LATERAL unnest(c.conkey, c.confkey) WITH ORDINALITY AS k(fk, pk, n) "
        + "JOIN pg_catalog.pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = k.fk "
        + "JOIN pg_catalog.pg_attribute ra ON ra.attrelid = c.confrelid AND ra.attnum = k.pk "
        + "WHERE c.contype = 'f' AND t.relname = ? "
        + "AND ns.nspname = coalesce(?, current_schema()) ORDER BY c.conname, k.n");
    try {
      statement.setString(1, table);
      statement.setString(2, schema);
      statement.closeOnCompletion();
      return statement.executeQuery();
    } catch (SQLException | RuntimeException e) {
      statement.close();
      throw e;
    }
  }
}
