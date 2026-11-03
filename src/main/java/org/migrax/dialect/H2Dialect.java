package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AlterColumn;
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
  public ScriptTool scriptTool() {
    return RUN_SCRIPT;
  }

  /** H2's RunScript tool. */
  private static final ScriptTool RUN_SCRIPT = new ScriptTool() {
    @Override
    public String runCommand() {
      return "java -cp h2.jar org.h2.tools.RunScript -url <url> -script <this file>";
    }

    @Override
    public String useSchema(String schema) {
      return "SET SCHEMA " + schema;
    }
  };

  @Override
  public boolean countsRowsForTableSize() {
    return true;
  }

  @Override
  public List<String> hibernateDialects(int hibernateMajor) {
    return List.of("org.hibernate.dialect.H2Dialect");
  }

  @Override
  public String eclipseLinkPlatform() {
    return "org.eclipse.persistence.platform.database.H2Platform";
  }

  @Override
  public String openJpaDictionary() {
    return "h2";
  }

  @Override
  public java.util.Optional<String> micronautDataUuidDefault() {
    return java.util.Optional.of("random_uuid()");
  }

  @Override
  public java.util.Optional<String> migrationLockLimit() {
    return java.util.Optional.of("H2 locks only within one process; use it for development only");
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
  public NameCase unquotedNames() {
    return NameCase.UPPER;
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
      throw new MigrationLockHeldException("Another Migrax invocation in this JVM is applying migrations.");
    }
    return localLock::unlock;
  }

  /** Renames the key in place: no new key is built, so nothing checks the table's rows. */
  @Override
  public String renameForeignKey(String table, String from, String to) {
    return "ALTER TABLE " + tableName(table) + " RENAME CONSTRAINT " + q(from) + " TO " + q(to);
  }

  @Override
  public ScratchSetup scratch() {
    return ScratchSetup.inMemory("jdbc:h2:mem:{name};DB_CLOSE_DELAY=-1", "sa", "");
  }

  /** Checks and comments in {@code INFORMATION_SCHEMA}. */
  @Override
  public SchemaCatalog schemaCatalog() {
    return new SchemaCatalog() {
      @Override
      public void readChecksAndComments(Connection connection, String catalog, String owner,
                                        List<String> tables, Details details) {
        for (String[] row : query(connection, "SELECT tc.TABLE_NAME, tc.CONSTRAINT_NAME, "
            + "cc.CHECK_CLAUSE FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN "
            + "INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA = "
            + "tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME WHERE "
            + "tc.CONSTRAINT_TYPE = 'CHECK' AND tc.TABLE_SCHEMA = ?", owner)) {
          details.addCheck(row[0], row[1], row[2]);
        }
        for (String[] row : query(connection, "SELECT TABLE_NAME, NULL, REMARKS FROM "
            + "INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? UNION ALL SELECT TABLE_NAME, "
            + "COLUMN_NAME, REMARKS FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ?",
            owner, owner)) {
          details.addComment(row[0], row[1], row[2]);
        }
      }
    };
  }
}
