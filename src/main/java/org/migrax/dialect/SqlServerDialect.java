package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * Microsoft SQL Server database dialect.
 *
 * @since 0.1.0
 */
public final class SqlServerDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "add", "authorization", "backup", "begin", "break", "browse", "bulk", "cascade",
      "checkpoint", "close", "clustered", "coalesce", "collate", "commit", "compute", "contains",
      "containstable", "continue", "convert", "current", "cursor", "database", "dbcc",
      "deallocate", "declare", "deny", "disk", "distributed", "double", "dump", "errlvl",
      "escape", "exec", "execute", "exit", "external", "file", "fillfactor", "freetext",
      "freetexttable", "function", "goto", "holdlock", "identity", "identity_insert",
      "identitycol", "if", "index", "key", "kill", "lineno", "load", "merge", "national",
      "nocheck", "nonclustered", "nullif", "of", "off", "offsets", "open", "opendatasource",
      "openquery", "openrowset", "openxml", "option", "over", "percent", "pivot", "plan",
      "precision", "print", "proc", "procedure", "public", "raiserror", "read", "readtext",
      "reconfigure", "replication", "restore", "restrict", "return", "revert", "revoke",
      "rollback", "rowcount", "rowguidcol", "rule", "save", "schema", "securityaudit",
      "semantickeyphrasetable", "semanticsimilaritydetailstable", "semanticsimilaritytable",
      "setuser", "shutdown", "statistics", "system_user", "tablesample", "textsize", "top",
      "tran", "transaction", "trigger", "truncate", "try_convert", "tsequal", "unpivot",
      "updatetext", "use", "varying", "view", "waitfor", "while", "within", "writetext");

  /** The schema every table name is qualified with, or null for the login's default schema. */
  private final String schema;

  public SqlServerDialect() {
    this(null);
  }

  private SqlServerDialect(String schema) {
    this.schema = schema;
  }

  /**
   * This dialect, writing every table and sequence as {@code <schema>.<name>}. SQL Server
   * can't switch a session's default schema, so SQL for several schemas names it: either a
   * schema name, or the placeholder {@code ${migrax.schema}} that Migrax fills in per schema.
   *
   * @param schema a schema name, or {@code ${migrax.schema}}
   * @return a dialect bound to that schema
   * @since 0.7.0
   */
  public SqlServerDialect inSchema(String schema) {
    return new SqlServerDialect(schema);
  }

  @Override
  public String id() {
    return "sqlserver";
  }

  @Override
  public String tableName(String table) {
    return schema == null ? q(table) : schemaSql() + "." + q(table);
  }

  /** The schema as written in SQL: the placeholder as it is, a name quoted. */
  private String schemaSql() {
    return schema.startsWith("${") ? schema : quote(schema);
  }

  /** A table's name as a string for OBJECT_ID and sp_rename, with the schema in front. */
  private String objectName(String table) {
    return schema == null ? table : schemaSql() + "." + table;
  }

  @Override
  public java.util.List<String> aliases() {
    return java.util.List.of("mssql");
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  protected String sequenceDefault(String name) {
    return "NEXT VALUE FOR " + tableName(name);
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar" -> "varchar(" + length(c) + ")";
      case "nvarchar" -> "nvarchar(" + length(c) + ")";
      case "text", "clob" -> "varchar(max)";
      case "varbinary" -> "varbinary(" + length(c) + ")";
      case "integer" -> "int";
      case "bigint" -> "bigint";
      case "boolean" -> "bit";
      case "decimal" -> numeric("decimal", c, "decimal(38,2)");
      case "double" -> "float(53)";
      case "float" -> "real";
      case "uuid" -> "uniqueidentifier";
      case "date" -> "date";
      case "time" -> "time";
      case "timestamp" -> "datetime2(6)";
      case "timestamptz" -> "datetimeoffset(6)";
      case "blob" -> "varbinary(max)";
      case "json" -> "nvarchar(max)";
      default -> null;
    };
  }

  @Override
  protected String identityType(SchemaModel.Column c) {
    return renderType(c) + " IDENTITY(1,1)";
  }

  @Override
  public String quote(String identifier) {
    return "[" + identifier.replace("]", "]]") + "]";
  }

  @Override
  protected String addColumn(String table, SchemaModel.Column column) {
    return "ALTER TABLE " + tableName(table) + " ADD " + column(column);
  }

  /**
   * SQL Server needs the full type and nullability on every ALTER COLUMN. Defaults are
   * separate constraints with generated names, so changing one looks the old one up first.
   */
  @Override
  protected String alterColumn(org.migrax.ops.AlterColumn change) {
    java.util.List<String> statements = new java.util.ArrayList<>();
    if (change.typeChanged() || change.sizeChanged() || change.nullabilityChanged()
        || !change.defaultChanged()) {
      statements.add(alterColumn(change.table(), change.before(), change.after()));
    }
    if (change.defaultChanged()) {
      String table = objectName(change.table()).replace("'", "''");
      String column = change.after().name().replace("'", "''");
      // One T-SQL batch without semicolons, so the script splitter keeps it together.
      statements.add("DECLARE @df sysname SELECT @df = d.name FROM sys.default_constraints d "
          + "JOIN sys.columns c ON c.default_object_id = d.object_id "
          + "WHERE d.parent_object_id = OBJECT_ID('" + table + "') AND c.name = '" + column
          + "' IF @df IS NOT NULL EXEC('ALTER TABLE " + tableName(change.table())
          + " DROP CONSTRAINT [' + @df + ']')");
      String value = change.after().defaultValue();
      if (value != null && !value.isBlank()) {
        statements.add("ALTER TABLE " + tableName(change.table()) + " ADD DEFAULT " + value
            + " FOR " + q(change.after().name()));
      }
    }
    return String.join(";\n", statements);
  }

  @Override
  protected String alterColumn(String table, SchemaModel.Column before, SchemaModel.Column after) {
    return "ALTER TABLE " + tableName(table) + " ALTER COLUMN " + q(after.name()) + " "
        + renderType(after)
        + (after.nullable() ? " NULL" : " NOT NULL");
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name) + " ON " + tableName(table);
  }

  @Override
  protected String renameTable(String from, String to) {
    return "EXEC sp_rename '" + objectName(from).replace("'", "''") + "', '"
        + to.replace("'", "''") + "'";
  }

  @Override
  protected String renameColumn(String table, String from, String to) {
    return "EXEC sp_rename '" + objectName(table).replace("'", "''") + "."
        + from.replace("'", "''") + "', '" + to.replace("'", "''") + "', 'COLUMN'";
  }

  @Override
  public String firstValue(String value, String from, String condition) {
    return "SELECT TOP 1 " + value + " FROM " + from + " WHERE " + condition;
  }

  @Override
  public boolean acceptsProduct(String productName) {
    return productName.contains("microsoft sql server");
  }

  /** An exclusive application lock (sp_getapplock) owned by the session. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    String sql = "DECLARE @result int; "
        + "EXEC @result = sp_getapplock @Resource=?, @LockMode='Exclusive', "
        + "@LockOwner='Session', @LockTimeout=0; SELECT @result";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, resource);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) < 0) {
          throw new MigrationLockHeldException("Another Migrax process is applying migrations.");
        }
      }
    }
    return () -> releaseMigrationLock(connection, resource);
  }

  private static void releaseMigrationLock(Connection connection, String resource)
      throws SQLException {
    String sql = "DECLARE @result int; "
        + "EXEC @result = sp_releaseapplock @Resource=?, @LockOwner='Session'; SELECT @result";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, resource);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) < 0) {
          throw new SQLException("Could not release the SQL Server migration lock.");
        }
      }
    }
  }

  /** SQL Server has no CREATE TABLE ... AS SELECT. */
  @Override
  public String copyTable(String target, String source, java.util.List<String> columns) {
    return "SELECT " + String.join(", ", columns.stream().map(this::q).toList()) + " INTO "
        + tableName(target) + " FROM " + tableName(source);
  }

  @Override
  protected String tableComment(String table, String comment) {
    return description(table, null, comment);
  }

  @Override
  protected String columnComment(String table, SchemaModel.Column column, String comment) {
    return description(table, column.name(), comment);
  }

  /**
   * SQL Server keeps comments as the MS_Description extended property. One statement without
   * semicolons, so the migration runs it whole: it updates, adds or drops the property.
   */
  private String description(String table, String column, String comment) {
    String target = "@level0type = N'SCHEMA', @level0name = @schema, @level1type = N'TABLE', "
        + "@level1name = " + nLiteral(table)
        + (column == null ? "" : ", @level2type = N'COLUMN', @level2name = " + nLiteral(column));
    String exists = "SELECT 1 FROM sys.extended_properties WHERE name = N'MS_Description' "
        + "AND major_id = OBJECT_ID(" + nLiteral(objectName(table)) + ") AND minor_id = "
        + (column == null ? "0" : "COLUMNPROPERTY(OBJECT_ID(" + nLiteral(objectName(table))
            + "), " + nLiteral(column) + ", 'ColumnId')");
    String set = comment == null ? ""
        : "@name = N'MS_Description', @value = " + nLiteral(comment) + ", " + target;
    return "DECLARE @schema sysname = " + (schema == null ? "SCHEMA_NAME()" : nLiteral(schema))
        + " IF EXISTS (" + exists + ") "
        + (comment == null
            ? "EXEC sp_dropextendedproperty @name = N'MS_Description', " + target
            : "EXEC sp_updateextendedproperty " + set + " ELSE EXEC sp_addextendedproperty "
                + set);
  }

  private static String nLiteral(String value) {
    return "N" + literal(value);
  }
}
