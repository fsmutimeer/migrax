package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import io.migrax.ops.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Base dialect implementation providing standard SQL generation for operations.
 *
 * <p>Identifiers are written unquoted, exactly as Hibernate writes them by default, so the
 * database folds their case the same way for both. Only reserved words and names that are not
 * plain identifiers are quoted.
 *
 * @since 0.1.0
 */
public abstract class AbstractDialect implements Dialect {
  private static final Pattern PLAIN_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  /** Words reserved by the SQL standard and by every supported database. */
  protected static final Set<String> COMMON_RESERVED_WORDS = Set.of(
      "all", "alter", "and", "any", "as", "asc", "between", "by", "case", "check", "column",
      "constraint", "create", "cross", "current_date", "current_time", "current_timestamp",
      "current_user", "default", "delete", "desc", "distinct", "drop", "else", "end", "except",
      "exists", "false", "fetch", "for", "foreign", "from", "full", "grant", "group", "having",
      "in", "inner", "insert", "intersect", "into", "is", "join", "left", "like", "natural",
      "not", "null", "on", "or", "order", "outer", "primary", "references", "right", "select",
      "session_user", "set", "some", "table", "then", "to", "true", "union", "unique", "update",
      "user", "using", "values", "when", "where", "with");

  @Override
  public String render(Operation o) {
    if (o instanceof CreateSequence x) return createSequence(x.sequence());
    if (o instanceof DropSequence x) return dropSequence(x.name());
    if (o instanceof CreateTable x) return createTable(x.table());
    if (o instanceof DropTable x) return "DROP TABLE " + q(x.table());
    if (o instanceof AddColumn x) return addColumn(x.table(), x.column());
    if (o instanceof DropColumn x) return "ALTER TABLE " + q(x.table()) + " DROP COLUMN " + q(x.column());
    if (o instanceof RenameColumn x) return renameColumn(x.table(), x.from(), x.to());
    if (o instanceof RenameTable x) return renameTable(x.from(), x.to());
    if (o instanceof AlterColumn x) return alterColumn(x);
    if (o instanceof AddPrimaryKey x) return addPrimaryKey(x.table(), x.key());
    if (o instanceof DropPrimaryKey x) return dropPrimaryKey(x.table(), x.constraintName());
    if (o instanceof AddIndex x) {
      return "CREATE " + (x.index().unique() ? "UNIQUE " : "") + "INDEX " + q(x.index().name())
          + " ON " + q(x.table()) + " (" + join(x.index().columns()) + ")";
    }
    if (o instanceof DropIndex x) return dropIndex(x.table(), x.name());
    if (o instanceof AddForeignKey x) {
      return "ALTER TABLE " + q(x.table()) + " ADD CONSTRAINT " + q(x.fk().name())
          + " FOREIGN KEY (" + join(x.fk().columns()) + ") REFERENCES "
          + q(x.fk().referencedTable()) + " (" + join(x.fk().referencedColumns()) + ")";
    }
    if (o instanceof DropForeignKey x) return dropForeignKey(x.table(), x.name());
    if (o instanceof AddUnique x) {
      return "ALTER TABLE " + q(x.table()) + " ADD CONSTRAINT " + q(x.name())
          + " UNIQUE (" + q(x.column()) + ")";
    }
    if (o instanceof DropUnique x) return dropUnique(x.table(), x.name());
    if (o instanceof RunSql x) return x.sql();
    throw new IllegalArgumentException("Unsupported operation " + o);
  }

  // ------------------------------------------------------------------ identifiers

  /**
   * Writes an identifier as Hibernate does: plain names unquoted, reserved words and other
   * names quoted.
   */
  protected String q(String identifier) {
    return needsQuoting(identifier) ? quote(identifier) : identifier;
  }

  protected boolean needsQuoting(String identifier) {
    return !PLAIN_IDENTIFIER.matcher(identifier).matches()
        || COMMON_RESERVED_WORDS.contains(identifier.toLowerCase(Locale.ROOT))
        || reservedWords().contains(identifier.toLowerCase(Locale.ROOT));
  }

  /** Extra reserved words for this database, in lower case. */
  protected Set<String> reservedWords() {
    return Set.of();
  }

  protected String join(List<String> values) {
    return values.stream().map(this::q).collect(Collectors.joining(", "));
  }

  // ------------------------------------------------------------------ tables and columns

  protected String createTable(SchemaModel.Table t) {
    List<String> parts = new ArrayList<>();
    for (SchemaModel.Column column : t.columns()) {
      parts.add(column(column));
    }
    if (t.primaryKey() != null) {
      String name = t.primaryKey().constraintName() == null || t.primaryKey().constraintName().isBlank()
          ? SchemaModel.primaryKeyConstraintName(t.name())
          : t.primaryKey().constraintName();
      parts.add("CONSTRAINT " + q(name) + " PRIMARY KEY (" + join(t.primaryKey().columns()) + ")");
    }
    for (SchemaModel.Column column : t.columns()) {
      if (column.unique()) {
        parts.add("CONSTRAINT " + q(SchemaModel.uniqueConstraintName(t.name(), column.name()))
            + " UNIQUE (" + q(column.name()) + ")");
      }
    }
    return "CREATE TABLE " + q(t.name()) + " (" + String.join(", ", parts) + ")";
  }

  protected String addColumn(String table, SchemaModel.Column column) {
    return "ALTER TABLE " + q(table) + " ADD COLUMN " + column(column);
  }

  /**
   * Column definition without UNIQUE: unique columns get a named constraint so that it can be
   * dropped later.
   */
  protected String column(SchemaModel.Column c) {
    String type = c.identity() ? identityType(c) : renderType(c);
    String d = c.defaultValue() != null
        ? " DEFAULT " + c.defaultValue()
        : (c.sequenceName() != null && sequenceDefault(c.sequenceName()) != null
            ? " DEFAULT " + sequenceDefault(c.sequenceName()) : "");
    return q(c.name()) + " " + type + d + (c.nullable() ? "" : " NOT NULL");
  }

  @Override
  public String columnType(SchemaModel.Column column) {
    return renderType(column);
  }

  protected String identityType(SchemaModel.Column c) {
    return renderType(c) + " GENERATED BY DEFAULT AS IDENTITY";
  }

  /**
   * SQL type of a column: an explicit {@code columnDefinition} is used as written, otherwise
   * the logical type is mapped by {@link #logicalType(SchemaModel.Column, String)}.
   */
  protected String renderType(SchemaModel.Column c) {
    if (c.sqlType() != null && !c.sqlType().equalsIgnoreCase(c.logicalType())) {
      return c.sqlType();
    }
    String logical = c.logicalType() != null ? c.logicalType() : c.sqlType();
    String sql = logical == null ? null : logicalType(c, logical.toLowerCase(Locale.ROOT));
    if (sql == null && c.sqlType() != null && !c.sqlType().isBlank()) {
      // Older snapshots may hold a database type such as VARCHAR(100) as the logical type.
      return c.sqlType();
    }
    if (sql == null) {
      throw new IllegalArgumentException("Column '" + c.name() + "' has type '" + logical
          + "', which the " + id() + " dialect does not map. Set "
          + "@Column(columnDefinition = \"<sql type>\") on the field.");
    }
    return sql;
  }

  /** Maps a logical type to this database's SQL type, or returns null if unsupported. */
  protected abstract String logicalType(SchemaModel.Column c, String logical);

  protected static int length(SchemaModel.Column c) {
    return c.length() == null ? 255 : c.length();
  }

  protected static String numeric(String name, SchemaModel.Column c, String unsized) {
    return c.precision() != null
        ? name + "(" + c.precision() + "," + (c.scale() == null ? 0 : c.scale()) + ")"
        : unsized;
  }

  // ------------------------------------------------------------------ alter

  protected String alterColumn(AlterColumn change) {
    return alterColumn(change.table(), change.before(), change.after());
  }

  protected String alterColumn(String table, SchemaModel.Column before, SchemaModel.Column after) {
    return "ALTER TABLE " + q(table) + " ALTER COLUMN " + q(after.name()) + " TYPE " + renderType(after);
  }

  protected String renameTable(String from, String to) {
    return "ALTER TABLE " + q(from) + " RENAME TO " + q(to);
  }

  protected String renameColumn(String table, String from, String to) {
    return "ALTER TABLE " + q(table) + " RENAME COLUMN " + q(from) + " TO " + q(to);
  }

  // ------------------------------------------------------------------ keys and indexes

  protected String addPrimaryKey(String table, SchemaModel.PrimaryKey key) {
    String name = key.constraintName() == null || key.constraintName().isBlank()
        ? SchemaModel.primaryKeyConstraintName(table)
        : key.constraintName();
    return "ALTER TABLE " + q(table) + " ADD CONSTRAINT " + q(name)
        + " PRIMARY KEY (" + join(key.columns()) + ")";
  }

  protected String dropPrimaryKey(String table, String constraintName) {
    if (constraintName == null || constraintName.isBlank()) {
      throw new IllegalStateException(
          "Cannot drop the primary key on '" + table
              + "' because its constraint name is unavailable in the schema snapshot.");
    }
    return "ALTER TABLE " + q(table) + " DROP CONSTRAINT " + q(constraintName);
  }

  protected String dropForeignKey(String table, String name) {
    return "ALTER TABLE " + q(table) + " DROP CONSTRAINT " + q(name);
  }

  protected String dropUnique(String table, String name) {
    return "ALTER TABLE " + q(table) + " DROP CONSTRAINT " + q(name);
  }

  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }

  // ------------------------------------------------------------------ sequences

  /** Column default that draws from a sequence, or null when the database has none. */
  protected String sequenceDefault(String name) {
    return "nextval('" + name + "')";
  }

  protected String createSequence(SchemaModel.Sequence s) {
    return "CREATE SEQUENCE " + q(s.name()) + " START WITH " + s.initialValue()
        + " INCREMENT BY " + s.allocationSize();
  }

  protected String dropSequence(String name) {
    return "DROP SEQUENCE " + q(name);
  }
}
