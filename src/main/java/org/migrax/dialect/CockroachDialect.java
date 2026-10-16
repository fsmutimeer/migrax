package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import java.sql.Connection;
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

  /**
   * CockroachDB changes a column type in place only when it widens it (a longer string, a
   * bigger integer). Any other change needs its experimental setting and runs only outside a
   * transaction, so such a migration turns the setting on and runs without one.
   */
  @Override
  public java.util.List<org.migrax.ops.Operation> prepare(
      java.util.List<org.migrax.ops.Operation> operations, SchemaModel before,
      SchemaModel after) {
    boolean rewrites = operations.stream().anyMatch(o -> o instanceof org.migrax.ops.AlterColumn x
        && (x.typeChanged() || x.sizeChanged())
        && !widens(columnType(x.before()), columnType(x.after())));
    if (!rewrites) {
      return operations;
    }
    java.util.List<org.migrax.ops.Operation> result = new java.util.ArrayList<>();
    result.add(new org.migrax.ops.RunSql("-- CockroachDB changes these column types only outside a "
        + "transaction, with its experimental setting.\n-- migrax:no-transaction\n"
        + "SET enable_experimental_alter_column_type_general = true", true));
    result.addAll(operations);
    return result;
  }

  /** Integer types by size. */
  private static final java.util.List<String> INTEGERS =
      java.util.List.of("int2", "smallint", "int4", "integer", "int", "int8", "bigint");

  /** Whether CockroachDB can change {@code from} to {@code to} in place. */
  static boolean widens(String from, String to) {
    java.util.regex.Matcher a = TYPE.matcher(from.toLowerCase(java.util.Locale.ROOT).strip());
    java.util.regex.Matcher b = TYPE.matcher(to.toLowerCase(java.util.Locale.ROOT).strip());
    if (!a.matches() || !b.matches()) {
      return false;
    }
    String nameA = a.group(1);
    String nameB = b.group(1);
    if (INTEGERS.contains(nameA) && INTEGERS.contains(nameB)) {
      return integerSize(nameB) >= integerSize(nameA);
    }
    boolean stringA = nameA.matches("varchar|character varying|string|text");
    boolean stringB = nameB.matches("varchar|character varying|string|text");
    if (stringA && stringB) {
      return b.group(2) == null || a.group(2) != null
          && Integer.parseInt(b.group(2)) >= Integer.parseInt(a.group(2));
    }
    if (nameA.equals(nameB) && nameA.matches("decimal|numeric|bytes|bytea")) {
      return b.group(2) == null || a.group(2) != null
          && Integer.parseInt(b.group(2)) >= Integer.parseInt(a.group(2))
          && java.util.Objects.equals(a.group(3), b.group(3));
    }
    return false;
  }

  private static final java.util.regex.Pattern TYPE = java.util.regex.Pattern.compile(
      "([a-z][a-z0-9 ]*?)\\s*(?:\\(\\s*(\\d+)\\s*(?:,\\s*(\\d+)\\s*)?\\))?");

  private static int integerSize(String name) {
    return name.equals("int2") || name.equals("smallint") ? 2
        : name.equals("int8") || name.equals("bigint") ? 8 : 4;
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
  public String renderOnline(org.migrax.ops.Operation operation) {
    if (operation instanceof org.migrax.ops.AddUnique x) {
      return "CREATE UNIQUE INDEX " + q(x.name()) + " ON " + q(x.table()) + " ("
          + q(x.column()) + ")";
    }
    return super.renderOnline(operation);
  }

  /**
   * CockroachDB has no session locks (its advisory lock functions do nothing), so the lock is a
   * row in {@code migrax_lock} ({@link TableMigrationLock}).
   */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    return TableMigrationLock.acquire(connection, resource);
  }

  /** CockroachDB has no triggers that change the row being written. */
  @Override
  public java.util.List<String> syncColumns(String table, String oldColumn, String newColumn) {
    return java.util.List.of();
  }

  @Override
  public java.util.List<String> dropSyncColumns(String table, String oldColumn,
                                                String newColumn) {
    return java.util.List.of();
  }

  /** CockroachDB views don't accept writes. */
  @Override
  public boolean updatableViews() {
    return false;
  }
}
