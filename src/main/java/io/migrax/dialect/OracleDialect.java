package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import io.migrax.ops.AlterColumn;
import java.util.Set;

/**
 * Oracle database dialect (12c and newer).
 *
 * <p>Names are written unquoted, so Oracle stores them in upper case, exactly like the
 * unquoted names Hibernate sends in its queries.
 *
 * @since 0.1.0
 */
public final class OracleDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "access", "add", "audit", "char", "cluster", "comment", "compress", "connect", "current",
      "date", "decimal", "exclusive", "file", "float", "identified", "immediate", "increment",
      "index", "initial", "integer", "level", "lock", "long", "maxextents", "minus", "mlslabel",
      "mode", "modify", "noaudit", "nocompress", "nowait", "number", "of", "offline", "online",
      "option", "pctfree", "prior", "privileges", "public", "raw", "rename", "resource",
      "revoke", "row", "rowid", "rownum", "rows", "session", "share", "size", "smallint",
      "start", "successful", "synonym", "sysdate", "trigger", "uid", "validate", "varchar",
      "varchar2", "view", "whenever");

  @Override
  public String id() {
    return "oracle";
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  protected String sequenceDefault(String name) {
    return q(name) + ".NEXTVAL";
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar" -> "varchar2(" + length(c) + " char)";
      case "nvarchar" -> "nvarchar2(" + length(c) + ")";
      case "text", "clob" -> "clob";
      case "varbinary" -> "raw(" + length(c) + ")";
      case "integer" -> "number(10,0)";
      case "bigint" -> "number(19,0)";
      case "boolean" -> "number(1,0)";
      case "decimal" -> numeric("number", c, "number(38,2)");
      case "double" -> "float(53)";
      case "float" -> "float(24)";
      case "uuid" -> "raw(16)";
      case "date", "time" -> "date";
      case "timestamp" -> "timestamp(6)";
      case "timestamptz" -> "timestamp(6) with time zone";
      case "blob" -> "blob";
      case "json" -> "json";
      default -> null;
    };
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  @Override
  protected String addColumn(String table, SchemaModel.Column column) {
    return "ALTER TABLE " + q(table) + " ADD (" + column(column) + ")";
  }

  /**
   * Oracle rejects MODIFY ... NULL or NOT NULL when the nullability does not change
   * (ORA-01451, ORA-01442), so it is written only when it changes.
   */
  @Override
  protected String alterColumn(AlterColumn change) {
    SchemaModel.Column after = change.after();
    StringBuilder definition = new StringBuilder(q(after.name()));
    if (change.typeChanged() || change.sizeChanged()
        || !change.nullabilityChanged() && !change.defaultChanged()) {
      definition.append(' ').append(renderType(after));
    }
    if (change.defaultChanged()) {
      definition.append(" DEFAULT ").append(after.defaultValue() == null
          || after.defaultValue().isBlank() ? "NULL" : after.defaultValue());
    }
    if (change.nullabilityChanged()) {
      definition.append(after.nullable() ? " NULL" : " NOT NULL");
    }
    return "ALTER TABLE " + q(change.table()) + " MODIFY (" + definition + ")";
  }

  @Override
  protected String dropPrimaryKey(String table, String constraintName) {
    return "ALTER TABLE " + q(table) + " DROP PRIMARY KEY";
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }
}
