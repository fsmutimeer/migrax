package io.migrax.dialect;

import io.migrax.model.SchemaModel;
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

  @Override
  public String id() {
    return "sqlserver";
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
    return "NEXT VALUE FOR " + q(name);
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
    return "ALTER TABLE " + q(table) + " ADD " + column(column);
  }

  /**
   * SQL Server needs the full type and nullability on every ALTER COLUMN. Defaults are
   * separate constraints with generated names, so changing one looks the old one up first.
   */
  @Override
  protected String alterColumn(io.migrax.ops.AlterColumn change) {
    java.util.List<String> statements = new java.util.ArrayList<>();
    if (change.typeChanged() || change.sizeChanged() || change.nullabilityChanged()
        || !change.defaultChanged()) {
      statements.add(alterColumn(change.table(), change.before(), change.after()));
    }
    if (change.defaultChanged()) {
      String table = change.table().replace("'", "''");
      String column = change.after().name().replace("'", "''");
      // One T-SQL batch without semicolons, so the script splitter keeps it together.
      statements.add("DECLARE @df sysname SELECT @df = d.name FROM sys.default_constraints d "
          + "JOIN sys.columns c ON c.default_object_id = d.object_id "
          + "WHERE d.parent_object_id = OBJECT_ID('" + table + "') AND c.name = '" + column
          + "' IF @df IS NOT NULL EXEC('ALTER TABLE " + q(change.table())
          + " DROP CONSTRAINT [' + @df + ']')");
      String value = change.after().defaultValue();
      if (value != null && !value.isBlank()) {
        statements.add("ALTER TABLE " + q(change.table()) + " ADD DEFAULT " + value + " FOR "
            + q(change.after().name()));
      }
    }
    return String.join(";\n", statements);
  }

  @Override
  protected String alterColumn(String table, SchemaModel.Column before, SchemaModel.Column after) {
    return "ALTER TABLE " + q(table) + " ALTER COLUMN " + q(after.name()) + " " + renderType(after)
        + (after.nullable() ? " NULL" : " NOT NULL");
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name) + " ON " + q(table);
  }

  @Override
  protected String renameTable(String from, String to) {
    return "EXEC sp_rename '" + from.replace("'", "''") + "', '" + to.replace("'", "''") + "'";
  }

  @Override
  protected String renameColumn(String table, String from, String to) {
    return "EXEC sp_rename '" + table.replace("'", "''") + "." + from.replace("'", "''")
        + "', '" + to.replace("'", "''") + "', 'COLUMN'";
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
          throw new SQLException("Another Migrax process is applying migrations.");
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
}
