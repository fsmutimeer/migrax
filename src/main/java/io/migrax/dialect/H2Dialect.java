package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import io.migrax.ops.AlterColumn;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * H2 database dialect (H2 2.x), for development and tests.
 *
 * @since 0.1.0
 */
public final class H2Dialect extends AbstractDialect {
  /** Migration locks per database URL; shared by every instance. */
  private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

  private static final Set<String> RESERVED = Set.of(
      "_rowid_", "array", "asymmetric", "authorization", "both", "cast", "current_catalog",
      "current_path", "current_role", "current_schema", "day", "groups", "hour", "if", "ilike",
      "interval", "key", "leading", "limit", "localtime", "localtimestamp", "minus", "minute",
      "month", "offset", "over", "partition", "qualify", "range", "regexp", "row", "rownum",
      "rows", "second", "symmetric", "system_user", "top", "trailing", "uescape", "unknown",
      "value", "window", "year");

  @Override
  public String id() {
    return "h2";
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
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text", "clob" -> "clob";
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
      case "json" -> "json";
      default -> null;
    };
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** H2 changes type and nullability with separate statements. */
  @Override
  protected String alterColumn(AlterColumn change) {
    SchemaModel.Column after = change.after();
    String prefix = "ALTER TABLE " + q(change.table()) + " ALTER COLUMN " + q(after.name());
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
  protected String dropIndex(String table, String name) {
    return "DROP INDEX " + q(name);
  }

  /** H2 has no lock across processes: this one only stops other Migrax runs in this JVM. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    String url = connection.getMetaData().getURL();
    ReentrantLock localLock = LOCKS.computeIfAbsent(url, ignored -> new ReentrantLock());
    if (!localLock.tryLock()) {
      throw new SQLException("Another Migrax invocation in this JVM is applying migrations.");
    }
    return localLock::unlock;
  }
}
