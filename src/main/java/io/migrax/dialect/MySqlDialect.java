package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * MySQL database dialect; MariaDB extends it.
 *
 * @since 0.1.0
 */
public class MySqlDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "accessible", "add", "analyze", "before", "bigint", "binary", "blob", "both", "call",
      "cascade", "change", "char", "character", "collate", "condition", "continue", "convert",
      "cume_dist", "cursor", "database", "databases", "dec", "decimal", "declare", "delayed",
      "dense_rank", "describe", "distinctrow", "div", "double", "dual", "each", "elseif",
      "enclosed", "escaped", "exit", "explain", "first_value", "float", "force", "fulltext",
      "generated", "get", "groups", "high_priority", "if", "ignore", "index", "infile", "inout",
      "int", "integer", "interval", "iterate", "key", "keys", "kill", "lag", "last_value",
      "lateral", "lead", "leading", "leave", "limit", "linear", "lines", "load", "lock", "long",
      "loop", "low_priority", "match", "maxvalue", "mod", "modifies", "nth_value", "ntile",
      "numeric", "of", "optimize", "option", "out", "outfile", "over", "partition",
      "percent_rank", "precision", "procedure", "purge", "range", "rank", "read", "reads", "real",
      "recursive", "regexp", "release", "rename", "repeat", "replace", "require", "restrict",
      "return", "revoke", "rlike", "row", "row_number", "rows", "schema", "schemas",
      "separator", "show", "signal", "smallint", "spatial", "specific", "sql", "sqlexception",
      "sqlstate", "sqlwarning", "ssl", "starting", "stored", "straight_join", "system",
      "terminated", "tinyint", "trailing", "trigger", "undo", "unlock", "unsigned", "usage",
      "varbinary", "varchar", "varying", "virtual", "while", "window", "write", "xor",
      "zerofill");

  @Override
  public String id() {
    return "mysql";
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text", "clob" -> "longtext";
      case "varbinary" -> "varbinary(" + length(c) + ")";
      case "integer" -> "int";
      case "bigint" -> "bigint";
      case "boolean" -> "bit";
      case "decimal" -> numeric("decimal", c, "decimal(38,2)");
      case "double" -> "double";
      case "float" -> "float";
      case "uuid" -> uuidType();
      case "date" -> "date";
      case "time" -> "time";
      case "timestamp", "timestamptz" -> "datetime(6)";
      case "blob" -> "longblob";
      case "json" -> "json";
      default -> null;
    };
  }

  /** Hibernate 6 stores UUIDs in MySQL as binary(16). */
  protected String uuidType() {
    return "binary(16)";
  }

  @Override
  protected String identityType(SchemaModel.Column c) {
    return renderType(c) + " AUTO_INCREMENT";
  }

  @Override
  public String quote(String identifier) {
    return "`" + identifier.replace("`", "``") + "`";
  }

  /**
   * MySQL has no sequences. Hibernate emulates them with a one-row table holding
   * {@code next_val}, so Migrax creates the same table.
   */
  @Override
  protected String createSequence(SchemaModel.Sequence s) {
    return "CREATE TABLE " + q(s.name()) + " (next_val bigint)" + ";\n"
        + "INSERT INTO " + q(s.name()) + " VALUES (" + s.initialValue() + ")";
  }

  @Override
  protected String dropSequence(String name) {
    return "DROP TABLE " + q(name);
  }

  @Override
  protected String sequenceDefault(String name) {
    return null;
  }

  @Override
  protected String alterColumn(String table, SchemaModel.Column before, SchemaModel.Column after) {
    return "ALTER TABLE " + q(table) + " MODIFY COLUMN " + column(after);
  }

  @Override
  protected String renameTable(String from, String to) {
    return "RENAME TABLE " + q(from) + " TO " + q(to);
  }

  @Override
  protected String dropPrimaryKey(String table, String constraintName) {
    return "ALTER TABLE " + q(table) + " DROP PRIMARY KEY";
  }

  @Override
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name) + " ON " + q(table);
  }

  @Override
  protected String dropForeignKey(String table, String name) {
    return "ALTER TABLE " + q(table) + " DROP FOREIGN KEY " + q(name);
  }

  @Override
  protected String dropUnique(String table, String name) {
    return "ALTER TABLE " + q(table) + " DROP INDEX " + q(name);
  }

  /** A named user lock (GET_LOCK), held by the session. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
      statement.setString(1, resource);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) {
          throw new SQLException("Another Migrax process is applying migrations.");
        }
      }
    }
    return () -> {
      try (PreparedStatement statement =
               connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        statement.setString(1, resource);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || result.getInt(1) != 1) {
            throw new SQLException("Could not release the MySQL migration lock.");
          }
        }
      }
    };
  }
}
