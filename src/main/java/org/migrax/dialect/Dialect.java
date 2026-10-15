package org.migrax.dialect;

import org.migrax.ops.Operation;

/**
 * Strategy interface for rendering schema operations into database-specific DDL.
 *
 * <p>Implementations are found with {@link java.util.ServiceLoader}: list the class in
 * {@code META-INF/services/org.migrax.dialect.Dialect} and give it a public no-argument
 * constructor. {@link Dialects} then picks it by name or JDBC URL.
 *
 * @since 0.1.0
 */
public interface Dialect {

  /**
   * Returns the unique identifier for this dialect (e.g., {@code "postgresql"}, {@code "mysql"}).
   *
   * @return the dialect identifier
   * @since 0.1.0
   */
  String id();

  /**
   * Other names accepted for this dialect by {@code --dialect}, for example {@code pg}.
   *
   * @return alternative names, lower case
   * @since 0.1.0
   */
  default java.util.List<String> aliases() {
    return java.util.List.of();
  }

  /**
   * Whether this dialect is the one for a JDBC URL. The default accepts URLs that start with
   * {@code jdbc:<id>:}.
   *
   * @param jdbcUrl JDBC URL, in lower case
   * @return {@code true} if this dialect writes SQL for that database
   * @since 0.1.0
   */
  default boolean acceptsUrl(String jdbcUrl) {
    return jdbcUrl.startsWith("jdbc:" + id() + ":");
  }

  /**
   * Whether this dialect is the one for a connection, by the database product name its driver
   * reports. The default accepts names that contain {@link #id()}.
   *
   * @param productName {@link java.sql.DatabaseMetaData#getDatabaseProductName()}, in lower case
   * @return {@code true} if this dialect handles that database
   * @since 0.1.0
   */
  default boolean acceptsProduct(String productName) {
    return productName.contains(id());
  }

  /**
   * Whether this dialect is the one for an open connection. The default checks the product
   * name ({@link #acceptsProduct}); a database that reports another product's name, such as
   * CockroachDB with the PostgreSQL driver, can look at the server itself.
   *
   * @param connection an open connection to the database
   * @return {@code true} if this dialect handles that database
   * @throws java.sql.SQLException when the connection's metadata can't be read
   * @since 0.2.0
   */
  default boolean acceptsConnection(java.sql.Connection connection) throws java.sql.SQLException {
    return acceptsProduct(connection.getMetaData().getDatabaseProductName()
        .toLowerCase(java.util.Locale.ROOT));
  }

  /**
   * Takes the database-wide lock that keeps two Migrax processes from migrating the same
   * database at once. It must not wait: when another session holds it, fail at once.
   * Databases without such a lock can't be migrated safely, so the default refuses.
   *
   * @param connection the connection that migrates; the lock belongs to its session
   * @param resource a name for the lock that is unique to the database, such as
   *     {@code org.migrax:3f2a...}
   * @return releases the lock when closed
   * @throws java.sql.SQLException when another process holds the lock, or locking failed
   * @since 0.1.0
   */
  default AutoCloseable acquireMigrationLock(java.sql.Connection connection, String resource)
      throws java.sql.SQLException {
    throw new java.sql.SQLException("Concurrent migration locking is not implemented for "
        + "database " + connection.getMetaData().getDatabaseProductName()
        + "; refusing to proceed.");
  }

  /**
   * Quotes a SQL identifier according to the database rules.
   *
   * @param identifier the identifier to quote
   * @return quoted identifier
   * @since 0.1.0
   */
  String quote(String identifier);

  /**
   * An identifier as this dialect writes it in generated SQL: quoted only when it has to be,
   * so it names the same object as the dialect's other statements.
   *
   * @param identifier the identifier
   * @return the identifier as written in SQL
   * @since 0.6.0
   */
  default String identifier(String identifier) {
    return identifier;
  }

  /**
   * A table's or sequence's name as this dialect writes it in generated SQL: the identifier,
   * with the schema in front when the dialect is bound to one.
   *
   * @param table the table or sequence name
   * @return the name as written in SQL
   * @since 0.7.0
   */
  default String tableName(String table) {
    return identifier(table);
  }

  /**
   * The statement that copies columns of a table into a new table, rows included.
   *
   * @param target the new table
   * @param source the table to copy from
   * @param columns the columns to copy
   * @return SQL statement
   * @since 0.6.0
   */
  default String copyTable(String target, String source, java.util.List<String> columns) {
    return "CREATE TABLE " + tableName(target) + " AS SELECT "
        + String.join(", ", columns.stream().map(this::identifier).toList()) + " FROM "
        + tableName(source);
  }

  /**
   * A query for one value from the first matching row, for use as a scalar subquery.
   *
   * @param value the value to select, such as {@code b.note}
   * @param from the table to read, with its alias
   * @param condition the WHERE condition
   * @return the SELECT statement, without parentheses
   * @since 0.7.0
   */
  default String firstValue(String value, String from, String condition) {
    return "SELECT " + value + " FROM " + from + " WHERE " + condition + " LIMIT 1";
  }

  /**
   * Renders the given schema operation into a database-specific DDL statement.
   *
   * @param operation the operation to render
   * @return SQL DDL string
   * @throws IllegalArgumentException if the operation is unsupported by this dialect
   * @since 0.1.0
   */
  String render(Operation operation);

  /**
   * Rewrites a list of operations into what this database can run, before they are written to
   * a migration file. The default returns them unchanged; SQLite replaces changes it can't make
   * in place with table rebuilds.
   *
   * @param operations the operations, in order
   * @param before the schema before them (renames in {@code operations} not yet applied)
   * @param after the schema after them
   * @return the operations to render
   * @since 0.2.0
   */
  default java.util.List<Operation> prepare(java.util.List<Operation> operations,
                                            org.migrax.model.SchemaModel before,
                                            org.migrax.model.SchemaModel after) {
    return operations;
  }

  /**
   * The SQL type this dialect writes for a column, without identity or constraints.
   *
   * @param column the column
   * @return SQL type such as {@code varchar(255)}
   * @since 0.1.0
   */
  default String columnType(org.migrax.model.SchemaModel.Column column) {
    throw new UnsupportedOperationException("columnType");
  }

  /**
   * Indicates whether this database engine executes DDL statements inside transactions.
   *
   * @return {@code true} if transactional DDL is supported; {@code false} otherwise
   * @since 0.1.0
   */
  default boolean transactionalDdl() {
    return true;
  }

  /**
   * Whether the SQL this dialect writes depends on the server's version, so that Migrax asks
   * the server when it can reach it. The default is {@code false}.
   *
   * @return {@code true} to have {@link #forServer} called with the server's version
   * @since 0.9.0
   */
  default boolean dependsOnServerVersion() {
    return false;
  }

  /**
   * This dialect for a server of the given version. The default returns this dialect.
   *
   * @param major the server's major version
   * @param minor the server's minor version
   * @return the dialect to use for that server
   * @since 0.9.0
   */
  default Dialect forServer(int major, int minor) {
    return this;
  }

  /**
   * Hibernate settings that tell the project's Hibernate the server version, so that its mapping
   * has the column types it uses on that server. Empty unless the version is known.
   *
   * @return the settings, possibly empty
   * @since 0.9.0
   */
  default java.util.Map<String, String> serverSettings() {
    return java.util.Map.of();
  }

  /**
   * The server version this dialect writes SQL for, as {@code major.minor}, recorded in the
   * snapshot so that commands without a database connection write the same SQL. Null unless
   * the SQL depends on it and the version is known.
   *
   * @return the version, or null
   * @since 0.9.0
   */
  default String serverVersion() {
    return null;
  }

  /**
   * The Docker image for {@code verify}'s throwaway database of this server, or null for the
   * default one.
   *
   * @return an image name, or null
   * @since 0.9.0
   */
  default String scratchImage() {
    return null;
  }
}
