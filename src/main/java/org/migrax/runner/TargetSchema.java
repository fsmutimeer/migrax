package org.migrax.runner;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The schema Migrax works in on a connection, for running the same migrations once per schema.
 *
 * <p>Most databases switch a connection's default schema, so unqualified names land in it. SQL
 * Server can't: a session always uses its login's default schema. There Migrax remembers the
 * schema for the connection, qualifies its own history tables with it, and migrations name it
 * with the {@code ${migrax.schema}} placeholder.
 *
 * @since 0.7.0
 */
public final class TargetSchema {
  /** The placeholder that holds the current schema in migration SQL. */
  public static final String PLACEHOLDER = "migrax.schema";

  private static final Map<Connection, String> NAMED =
      Collections.synchronizedMap(new WeakHashMap<>());

  private TargetSchema() {}

  /**
   * Points the connection at a schema: MySQL and MariaDB switch the database, SQL Server
   * remembers it, the others switch the default schema.
   *
   * @throws IllegalArgumentException on SQLite, which has no schemas
   */
  public static void use(Connection connection, String schema) throws SQLException {
    String product = product(connection);
    if (product.contains("mysql") || product.contains("mariadb")) {
      connection.setCatalog(schema);
    } else if (product.contains("sqlite")) {
      throw new IllegalArgumentException("SQLite has no schemas.");
    } else if (product.contains("sql server")) {
      NAMED.put(connection, schema);
    } else {
      connection.setSchema(schema);
    }
  }

  /** The schema set with {@link #use} on SQL Server, or null for the login's default schema. */
  static String named(Connection connection) {
    return NAMED.get(connection);
  }

  /**
   * The schema unqualified names refer to on this connection, or null when the driver can't
   * tell: the one set with {@link #use}, else the connection's own.
   */
  public static String current(Connection connection) {
    String named = named(connection);
    if (named != null) {
      return named;
    }
    try {
      String schema = product(connection).matches(".*(mysql|mariadb).*")
          ? connection.getCatalog() : connection.getSchema();
      return schema == null || schema.isBlank() ? null : schema;
    } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
      return null;
    }
  }

  /** A Migrax table's name in SQL, with the schema set with {@link #use} on SQL Server. */
  static String table(Connection connection, String name) {
    String named = named(connection);
    return named == null ? name : "[" + named.replace("]", "]]") + "]." + name;
  }

  private static String product(Connection connection) throws SQLException {
    return connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
  }
}
