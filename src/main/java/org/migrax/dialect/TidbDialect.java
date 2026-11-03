package org.migrax.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TiDB dialect. TiDB speaks MySQL's protocol and is reached with the MySQL driver
 * ({@code jdbc:mysql://host:4000/db}), which reports "MySQL", so it is recognized from the
 * server's {@code VERSION()} ({@link #acceptsConnection}). It writes MySQL's SQL, as
 * Hibernate's MySQL dialect maps the entities for TiDB.
 *
 * <p>What differs from MySQL: no triggers and no writable views (so {@code --zero-downtime}
 * can't rename), no stored procedures, and check constraints only with
 * {@code tidb_enable_check_constraint}.
 *
 * @since 0.12.0
 */
public final class TidbDialect extends MySqlDialect {

  @Override
  public String id() {
    return "tidb";
  }

  @Override
  public String describeServer(String product, String version) {
    return "TiDB " + version.replaceFirst(".*-TiDB-", "") + " (MySQL protocol)";
  }

  /** TiDB enforces check constraints only with tidb_enable_check_constraint (7.2 and newer). */
  @Override
  public java.util.Optional<String> unenforcedChecks(Connection connection) {
    return checksEnabled(connection) ? java.util.Optional.empty()
        : java.util.Optional.of("TiDB ignores them while tidb_enable_check_constraint is OFF; "
            + "the entities' @Check conditions are not checked on this server. Set it with "
            + "SET GLOBAL tidb_enable_check_constraint = ON");
  }

  private static boolean checksEnabled(Connection connection) {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT @@tidb_enable_check_constraint")) {
      return result.next() && result.getInt(1) == 1;
    } catch (SQLException e) {
      return false;
    }
  }

  /** The URL is the MySQL driver's: {@link #acceptsConnection} decides. */
  @Override
  public boolean acceptsUrl(String jdbcUrl) {
    return false;
  }

  @Override
  public boolean acceptsProduct(String productName) {
    return productName.toLowerCase(Locale.ROOT).contains("tidb");
  }

  @Override
  public boolean acceptsConnection(Connection connection) throws SQLException {
    return isTidb(connection);
  }

  /** True when the server behind a MySQL connection is TiDB. */
  static boolean isTidb(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
      return product != null && product.toLowerCase(Locale.ROOT).contains("tidb");
    }
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT VERSION()")) {
      return result.next() && result.getString(1) != null
          && result.getString(1).contains("TiDB");
    }
  }

  /**
   * TiDB has no {@code CREATE TABLE ... AS SELECT}: the backup gets the source table's
   * structure and all its rows, so it holds the kept columns and the others.
   */
  @Override
  public String copyTable(String target, String source, java.util.List<String> columns) {
    return "CREATE TABLE " + tableName(target) + " LIKE " + tableName(source) + ";\n"
        + "INSERT INTO " + tableName(target) + " SELECT * FROM " + tableName(source);
  }

  /** TiDB has no triggers. */
  @Override
  public java.util.List<String> syncColumns(String table, String oldColumn, String newColumn) {
    return java.util.List.of();
  }

  @Override
  public java.util.List<String> dropSyncColumns(String table, String oldColumn,
                                                String newColumn) {
    return java.util.List.of();
  }

  /** TiDB views are read-only. */
  @Override
  public boolean updatableViews() {
    return false;
  }

  @Override
  public ScratchSetup scratch() {
    return new ScratchSetup("pingcap/tidb:v8.5.3", 4000,
        "jdbc:mysql://127.0.0.1:{port}/test?useSSL=false", "root", "", 120);
  }

  /** A check in TiDB's SHOW CREATE TABLE: {@code CONSTRAINT `name` CHECK ((condition))}. */
  private static final Pattern CHECK = Pattern.compile(
      "(?m)CONSTRAINT `([^`]+)` CHECK \\((.*)\\)(?: /\\*.*\\*/)?(?: NOT ENFORCED)?,?$");

  /** TiDB doesn't list checks in TABLE_CONSTRAINTS; its own view has their tables. */
  @Override
  public SchemaCatalog schemaCatalog() {
    return new Catalog(true) {
      @Override
      void readChecks(Connection connection, String owner, List<String> tables,
                      Details details) {
        if (query(connection, "SELECT 1 FROM information_schema.TABLES WHERE "
            + "TABLE_SCHEMA = 'INFORMATION_SCHEMA' AND TABLE_NAME = 'TIDB_CHECK_CONSTRAINTS'")
            .isEmpty()) {
          // Older TiDB versions tell which table a check belongs to only in SHOW CREATE TABLE.
          for (String table : tables) {
            for (String[] row : query(connection, "SHOW CREATE TABLE `"
                + table.replace("`", "``") + "`")) {
              Matcher check = CHECK.matcher(row.length > 1 ? row[1] : "");
              while (check.find()) {
                details.addCheck(table, check.group(1), unwrapCheck(check.group(2)));
              }
            }
          }
          super.readChecks(connection, owner, tables, details);
        } else {
          addChecks(connection, "SELECT TABLE_NAME, CONSTRAINT_NAME, CHECK_CLAUSE FROM "
              + "information_schema.TIDB_CHECK_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = ?", owner,
              details);
        }
      }
    };
  }
}
