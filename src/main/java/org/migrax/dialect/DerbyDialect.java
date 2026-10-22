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
 * Apache Derby (10.16 and newer) dialect, embedded or as a network server. Derby keeps no
 * comments, takes no sequence value as a column default, and its decimals have at most 31
 * digits; the migration lock is a row in {@code migrax_lock}.
 *
 * @since 0.12.0
 */
public final class DerbyDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "add", "allocate", "are", "assertion", "at", "authorization", "avg", "begin", "bigint",
      "bit", "boolean", "both", "call", "cascade", "cascaded", "cast", "char", "character",
      "character_length", "char_length", "close", "coalesce", "collate", "collation", "commit",
      "connect", "connection", "constraints", "continue", "convert", "corresponding", "current",
      "current_role", "cursor", "deallocate", "dec", "decimal", "declare", "deferrable",
      "deferred", "describe", "diagnostics", "disconnect", "double", "escape", "exception",
      "exec", "execute", "explain", "external", "first", "float", "found", "function", "get",
      "getcurrentconnection", "global", "go", "goto", "hour", "identity", "immediate",
      "indicator", "initially", "inout", "input", "insensitive", "int", "integer", "isolation",
      "key", "last", "lower", "ltrim", "match", "max", "min", "minute", "national", "nchar",
      "nvarchar", "next", "no", "none", "nullif", "numeric", "of", "only", "open", "option",
      "output", "overlaps", "pad", "partial", "prepare", "preserve", "prior", "privileges",
      "procedure", "public", "read", "real", "relative", "restrict", "revoke", "rollback",
      "rows", "row_number", "rtrim", "schema", "scroll", "second", "smallint", "space", "sql",
      "sqlcode", "sqlerror", "sqlstate", "substr", "substring", "sum", "system_user",
      "temporary", "timezone_hour", "timezone_minute", "transaction", "translate",
      "translation", "trim", "unknown", "upper", "varchar", "varying", "view", "whenever",
      "work", "write", "xml", "xmlexists", "xmlparse", "xmlquery", "xmlserialize", "year");

  @Override
  public String id() {
    return "derby";
  }

  /** The driver reports "Apache Derby". */
  @Override
  public boolean acceptsProduct(String productName) {
    return productName.toLowerCase(Locale.ROOT).contains("derby");
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  /** Derby takes no sequence value as a column default; Hibernate draws ids itself. */
  @Override
  protected String sequenceDefault(String name) {
    return null;
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text", "clob", "json" -> "clob";
      case "varbinary" -> "varchar(" + length(c) + ") for bit data";
      case "integer" -> "integer";
      case "bigint" -> "bigint";
      case "boolean" -> "boolean";
      case "decimal" -> numeric("decimal", c, "decimal(31,2)");
      case "double" -> "double";
      case "float" -> "real";
      // No UUID type: 16 bytes, as Hibernate stores it there.
      case "uuid" -> "char(16) for bit data";
      case "date" -> "date";
      case "time" -> "time";
      case "timestamp", "timestamptz" -> "timestamp";
      case "blob" -> "blob";
      default -> null;
    };
  }

  @Override
  public NameCase unquotedNames() {
    return NameCase.UPPER;
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** Derby changes type, nullability and default with separate statements. */
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
      statements.add(prefix + (after.nullable() ? " NULL" : " NOT NULL"));
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
  protected String renameTable(String from, String to) {
    return "RENAME TABLE " + tableName(from) + " TO " + q(to);
  }

  @Override
  protected String renameColumn(String table, String from, String to) {
    return "RENAME COLUMN " + tableName(table) + "." + q(from) + " TO " + q(to);
  }

  /**
   * Derby changes a column's type in place only to lengthen a VARCHAR (also FOR BIT DATA), a
   * CLOB or a BLOB. Any other change rebuilds the column: a new column of the new type, the
   * values copied, the old column dropped (with what depends on it) and the new one renamed;
   * then NOT NULL and the indexes, keys and checks that use it come back.
   */
  @Override
  public List<org.migrax.ops.Operation> prepare(List<org.migrax.ops.Operation> operations,
                                                SchemaModel before, SchemaModel after) {
    if (operations.stream().noneMatch(o -> o instanceof AlterColumn x && rebuilds(x))) {
      return operations;
    }
    List<org.migrax.ops.Operation> result = new ArrayList<>();
    for (org.migrax.ops.Operation operation : operations) {
      if (!(operation instanceof AlterColumn change) || !rebuilds(change)) {
        result.add(operation);
        continue;
      }
      String table = change.table();
      SchemaModel.Column column = change.after();
      SchemaModel.Table target = after.table(table);
      if (target != null && target.primaryKey() != null
          && target.primaryKey().columns().contains(column.name())) {
        throw new IllegalStateException("Derby can't change the type of " + table + "."
            + column.name() + ", a primary key column: write this migration by hand.");
      }
      String temporary = "migrax_new_" + column.name();
      result.add(new org.migrax.ops.AddColumn(table, new SchemaModel.Column(temporary,
          column.sqlType(), true, column.length(), column.precision(), column.scale(),
          column.defaultValue(), false, false, null, column.logicalType())));
      result.add(new org.migrax.ops.RunSql("UPDATE " + tableName(table) + " SET "
          + identifier(temporary) + " = CAST(" + identifier(column.name()) + " AS "
          + renderType(column) + ")", true));
      result.add(new org.migrax.ops.DropColumn(table, column.name()));
      result.add(new org.migrax.ops.RenameColumn(table, temporary, column.name()));
      if (!column.nullable()) {
        result.add(new AlterColumn(table, new SchemaModel.Column(column.name(),
            column.sqlType(), true, column.length(), column.precision(), column.scale(),
            column.defaultValue(), false, false, null, column.logicalType()), column));
      }
      result.addAll(restore(table, column.name(), after, operations));
    }
    return result;
  }

  private boolean rebuilds(AlterColumn change) {
    if (!change.typeChanged() && !change.sizeChanged()) {
      return false;
    }
    String from = renderType(change.before()).toLowerCase(Locale.ROOT);
    String to = renderType(change.after()).toLowerCase(Locale.ROOT);
    java.util.regex.Matcher a = LENGTH.matcher(from);
    java.util.regex.Matcher b = LENGTH.matcher(to);
    return !(a.matches() && b.matches() && a.group(1).equals(b.group(1))
        && a.group(3).equals(b.group(3))
        && Integer.parseInt(b.group(2)) >= Integer.parseInt(a.group(2)));
  }

  /** A type Derby lengthens in place: VARCHAR(n), VARCHAR(n) FOR BIT DATA, CLOB(n), BLOB(n). */
  private static final java.util.regex.Pattern LENGTH = java.util.regex.Pattern.compile(
      "(varchar|clob|blob)\\((\\d+)\\)(\\s*for bit data|)");

  /**
   * The indexes, unique key, foreign keys (also of other tables pointing at it) and checks that
   * use a column, as the target schema has them, unless the same migration adds them anyway.
   */
  private static List<org.migrax.ops.Operation> restore(String table, String column,
                                                        SchemaModel after,
                                                        List<org.migrax.ops.Operation> planned) {
    List<org.migrax.ops.Operation> restored = new ArrayList<>();
    SchemaModel.Table target = after.table(table);
    if (target == null) {
      return restored;
    }
    for (SchemaModel.Index index : target.indexes()) {
      if (index.columns().contains(column)) {
        restored.add(new org.migrax.ops.AddIndex(table, index));
      }
    }
    SchemaModel.Column now = target.column(column);
    if (now != null && now.unique()) {
      restored.add(new org.migrax.ops.AddUnique(table, column,
          SchemaModel.uniqueConstraintName(table, column)));
    }
    for (SchemaModel.Check check : target.checks()) {
      if (check.expression().toLowerCase(Locale.ROOT)
          .contains(column.toLowerCase(Locale.ROOT))) {
        restored.add(new org.migrax.ops.AddCheck(table, check));
      }
    }
    for (SchemaModel.Table other : after.tables()) {
      for (SchemaModel.ForeignKey key : other.foreignKeys()) {
        boolean own = other.name().equals(table) && key.columns().contains(column);
        boolean pointing = key.referencedTable().equals(table)
            && key.referencedColumns().contains(column);
        if (own || pointing) {
          restored.add(new org.migrax.ops.AddForeignKey(other.name(), key));
        }
      }
    }
    restored.removeIf(planned::contains);
    return restored;
  }

  /** Derby wants RESTRICT: it refuses to drop a sequence something still uses. */
  @Override
  protected String dropSequence(String name) {
    return "DROP SEQUENCE " + tableName(name) + " RESTRICT";
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }

  @Override
  public String render(org.migrax.ops.Operation operation) {
    if (operation instanceof org.migrax.ops.SetComment) {
      return "-- Derby keeps no comments";
    }
    return super.render(operation);
  }

  /** Derby copies a query's structure only (WITH NO DATA), so the rows follow in an INSERT. */
  @Override
  public String copyTable(String target, String source, List<String> columns) {
    String list = String.join(", ", columns.stream().map(this::identifier).toList());
    return "CREATE TABLE " + tableName(target) + " AS SELECT " + list + " FROM "
        + tableName(source) + " WITH NO DATA;\nINSERT INTO " + tableName(target) + " SELECT "
        + list + " FROM " + tableName(source);
  }

  @Override
  public String firstValue(String value, String from, String condition) {
    return "SELECT " + value + " FROM " + from + " WHERE " + condition
        + " FETCH FIRST 1 ROWS ONLY";
  }

  /** A row in {@code migrax_lock}: Derby has no lock functions. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    return TableMigrationLock.acquire(connection, resource, "timestamp", false);
  }
}
