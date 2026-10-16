package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AlterColumn;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HyperSQL (HSQLDB 2.7 and newer) dialect, in memory, in files or as a server. Its SQL is close
 * to the standard and to H2's; the migration lock is a row in {@code migrax_lock}, so it also
 * works across processes for a file or server database.
 *
 * @since 0.12.0
 */
public final class HsqldbDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "absolute", "action", "array", "asymmetric", "authorization", "both", "cast", "collate",
      "condition", "corresponding", "cube", "current_catalog", "current_path", "current_role",
      "current_schema", "cursor", "cycle", "day", "do", "domain", "dynamic", "each", "element",
      "escape", "every", "filter", "first", "free", "function", "global", "groups", "hour",
      "if", "interval", "key", "language", "large", "last", "lateral", "leading", "limit",
      "local", "localtime", "localtimestamp", "match", "member", "merge", "method", "minute",
      "module", "month", "new", "next", "no", "none", "normalize", "of", "offset", "old",
      "only", "open", "over", "overlaps", "partition", "position", "range", "read", "recursive",
      "ref", "release", "result", "return", "returns", "revoke", "rollback", "rollup", "row",
      "rows", "savepoint", "scope", "search", "second", "sensitive", "similar", "specific",
      "start", "static", "symmetric", "system", "system_user", "timezone_hour",
      "timezone_minute", "trailing", "translate", "treat", "trigger", "trim", "unknown", "value",
      "window", "within", "without", "year");

  @Override
  public String id() {
    return "hsqldb";
  }

  @Override
  public java.util.List<String> aliases() {
    return java.util.List.of("hsql", "hypersql");
  }

  /** The driver reports "HSQL Database Engine". */
  @Override
  public boolean acceptsProduct(String productName) {
    return productName.toLowerCase(Locale.ROOT).contains("hsql");
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  /**
   * HyperSQL takes no sequence value as a column default ({@code GENERATED ... AS SEQUENCE}
   * is its own clause); Hibernate draws ids from the sequence itself.
   */
  @Override
  protected String sequenceDefault(String name) {
    return null;
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text", "clob", "json" -> "clob";
      case "varbinary" -> "varbinary(" + length(c) + ")";
      case "integer" -> "integer";
      case "bigint" -> "bigint";
      case "boolean" -> "boolean";
      case "decimal" -> numeric("numeric", c, "numeric(38,2)");
      case "double" -> "double precision";
      case "float" -> "real";
      case "uuid" -> "uuid";
      case "date" -> "date";
      case "time" -> "time";
      case "timestamp" -> "timestamp(6)";
      case "timestamptz" -> "timestamp(6) with time zone";
      case "blob" -> "blob";
      default -> null;
    };
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** HyperSQL changes type, nullability and default with separate statements. */
  @Override
  protected String alterColumn(AlterColumn change) {
    SchemaModel.Column after = change.after();
    String prefix = "ALTER TABLE " + tableName(change.table()) + " ALTER COLUMN "
        + q(after.name());
    List<String> statements = new ArrayList<>();
    if (change.typeChanged() || change.sizeChanged()) {
      statements.add(prefix + " SET DATA TYPE " + renderType(after));
    }
    if (change.nullabilityChanged()) {
      statements.add(prefix + (after.nullable() ? " SET NULL" : " SET NOT NULL"));
    }
    if (change.defaultChanged()) {
      statements.add(prefix + (after.defaultValue() == null || after.defaultValue().isBlank()
          ? " DROP DEFAULT" : " SET DEFAULT " + after.defaultValue()));
    }
    if (statements.isEmpty()) {
      statements.add(prefix + " SET DATA TYPE " + renderType(after));
    }
    return String.join(";\n", statements);
  }

  @Override
  protected String renameColumn(String table, String from, String to) {
    return "ALTER TABLE " + tableName(table) + " ALTER COLUMN " + q(from) + " RENAME TO "
        + q(to);
  }

  /** HyperSQL wants the query in parentheses and WITH DATA to copy the rows. */
  @Override
  public String copyTable(String target, String source, List<String> columns) {
    return "CREATE TABLE " + tableName(target) + " AS (SELECT "
        + String.join(", ", columns.stream().map(this::identifier).toList()) + " FROM "
        + tableName(source) + ") WITH DATA";
  }

  /** HyperSQL takes no NULL comment: an empty one removes it. */
  @Override
  protected String commentValue(String comment) {
    return comment == null ? "''" : super.commentValue(comment);
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }

  @Override
  public boolean updatableViews() {
    return true;
  }

  /** DDL statements commit at once. */
  @Override
  public boolean transactionalDdl() {
    return false;
  }

  /** A row in {@code migrax_lock}: HyperSQL has no lock functions. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    return TableMigrationLock.acquire(connection, resource, "timestamp");
  }
}
