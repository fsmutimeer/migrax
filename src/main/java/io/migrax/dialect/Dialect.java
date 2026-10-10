package io.migrax.dialect;

import io.migrax.ops.Operation;

/**
 * Strategy interface for rendering schema operations into database-specific DDL.
 *
 * <p>Implementations are found with {@link java.util.ServiceLoader}: list the class in
 * {@code META-INF/services/io.migrax.dialect.Dialect} and give it a public no-argument
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
   *     {@code io.migrax:3f2a...}
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
                                            io.migrax.model.SchemaModel before,
                                            io.migrax.model.SchemaModel after) {
    return operations;
  }

  /**
   * The SQL type this dialect writes for a column, without identity or constraints.
   *
   * @param column the column
   * @return SQL type such as {@code varchar(255)}
   * @since 0.1.0
   */
  default String columnType(io.migrax.model.SchemaModel.Column column) {
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
}
