package org.migrax.dialect;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

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
}
