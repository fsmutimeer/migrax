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
