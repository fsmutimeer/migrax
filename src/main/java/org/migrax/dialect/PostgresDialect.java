package org.migrax.dialect;

import org.migrax.model.SchemaModel;
import org.migrax.ops.AlterColumn;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PostgreSQL database dialect.
 *
 * @since 0.1.0
 */
public class PostgresDialect extends AbstractDialect {
  private static final Set<String> RESERVED = Set.of(
      "analyse", "analyze", "array", "asymmetric", "authorization", "binary", "both", "cast",
      "collate", "collation", "concurrently", "current_catalog", "current_role",
      "current_schema", "deferrable", "do", "freeze", "ilike", "initially", "isnull", "lateral",
      "leading", "limit", "localtime", "localtimestamp", "notnull", "offset", "only", "overlaps",
      "placing", "returning", "similar", "symmetric", "tablesample", "trailing", "variadic",
      "verbose", "window");

  @Override
  public String id() {
    return "postgresql";
  }

  /**
   * {@code CURRENT_TIMESTAMP} goes into a {@code TIMESTAMP} column in the session's time zone,
   * which is this machine's zone for Migrax but the server's zone for psql: the script names
   * the zone, so its rows match the ones Migrax writes itself. CockroachDB and YugabyteDB
   * inherit this.
   */
  @Override
  public String scriptTimestamp(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT current_setting('TimeZone')")) {
      String zone = result.next() ? result.getString(1) : null;
      return zone == null ? "CURRENT_TIMESTAMP"
          : "(CURRENT_TIMESTAMP AT TIME ZONE '" + zone.replace("'", "''") + "')";
    }
  }

  @Override
  public ScriptTool scriptTool() {
    return PSQL;
  }

  /** PostgreSQL's psql. */
  private static final ScriptTool PSQL = new ScriptTool() {
    @Override
    public String runCommand() {
      return "psql -v ON_ERROR_STOP=1 -f <this file>";
    }
  };

  @Override
  public java.util.Optional<String> tableSizeQuery() {
    return java.util.Optional.of("SELECT c.reltuples::bigint, pg_total_relation_size(c.oid) "
        + "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
        + "WHERE c.relname = ? AND n.nspname = current_schema()");
  }

  @Override
  public boolean rendersOnline() {
    return true;
  }

  /** The lock checks of {@code migrax lint} are written for PostgreSQL. */
  @Override
  public boolean lintsLockingDdl() {
    return true;
  }

  /** pg_dump writes psql scripts. */
  @Override
  public boolean dumpsHavePsqlCommands() {
    return true;
  }

  /** The PostgreSQL driver also reaches CockroachDB and YugabyteDB. */
  @Override
  public boolean urlReachesOtherDatabases() {
    return true;
  }

  /**
   * Hibernate 5's unversioned PostgreSQLDialect describes very old servers; its resolver picks
   * PostgreSQL10Dialect for current ones.
   */
  @Override
  public List<String> hibernateDialects(int hibernateMajor) {
    if (hibernateMajor == 4) {
      return List.of("org.hibernate.dialect.PostgreSQL9Dialect",
          "org.hibernate.dialect.PostgreSQL82Dialect", "org.hibernate.dialect.PostgreSQLDialect");
    }
    return List.of(hibernateMajor == 5 ? "org.hibernate.dialect.PostgreSQL10Dialect"
        : "org.hibernate.dialect.PostgreSQLDialect");
  }

  @Override
  public String eclipseLinkPlatform() {
    return "org.eclipse.persistence.platform.database.PostgreSQLPlatform";
  }

  @Override
  public String openJpaDictionary() {
    return "postgres";
  }

  /** Large objects go to oid columns, where the ORMs write bytea or text. */
  @Override
  public boolean largeObjectTypesFromOrm() {
    return true;
  }

  /** Plain bytes go to bytea, not to a large object. */
  @Override
  public java.util.Optional<String> plainBytesType() {
    return java.util.Optional.of("bytea");
  }

  @Override
  public java.util.Optional<String> micronautDataUuidDefault() {
    return java.util.Optional.of("gen_random_uuid()");
  }

  @Override
  public java.util.List<String> aliases() {
    return java.util.List.of("postgres", "pg");
  }

  /** PostgreSQL itself; CockroachDB and YugabyteDB report the same product name. */
  @Override
  public boolean acceptsConnection(Connection connection) throws SQLException {
    return super.acceptsConnection(connection) && !CockroachDialect.isCockroach(connection)
        && !YugabyteDialect.isYugabyte(connection);
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text" -> "text";
      // Hibernate stores @Lob values as PostgreSQL large objects.
      case "clob", "blob" -> "oid";
      case "varbinary" -> "bytea";
      case "integer" -> "integer";
      case "bigint" -> "bigint";
      case "boolean" -> "boolean";
      case "decimal" -> numeric("numeric", c, "numeric(38,2)");
      case "double" -> "double precision";
      case "float" -> "real";
      case "uuid" -> "uuid";
      case "date" -> "date";
      case "time" -> "time(6)";
      case "timestamp" -> "timestamp(6)";
      case "timestamptz" -> "timestamp(6) with time zone";
      case "json" -> "jsonb";
      default -> null;
    };
  }

  /**
   * Non-blocking form of an operation on an existing table, for migrations marked
   * {@code -- migrax:no-transaction}: indexes are built CONCURRENTLY, unique constraints are
   * attached to a concurrently built index, and foreign keys and check constraints are added
   * NOT VALID and then validated without blocking writes.
   */
  @Override
  public String renderOnline(org.migrax.ops.Operation operation) {
    if (operation instanceof org.migrax.ops.AddIndex x) {
      return "CREATE " + (x.index().unique() ? "UNIQUE " : "") + "INDEX CONCURRENTLY "
          + q(x.index().name()) + " ON " + q(x.table()) + " (" + join(x.index().columns()) + ")";
    }
    if (operation instanceof org.migrax.ops.AddUnique x) {
      return "CREATE UNIQUE INDEX CONCURRENTLY " + q(x.name()) + " ON " + q(x.table())
          + " (" + q(x.column()) + ");\nALTER TABLE " + q(x.table()) + " ADD CONSTRAINT "
          + q(x.name()) + " UNIQUE USING INDEX " + q(x.name());
    }
    if (operation instanceof org.migrax.ops.AddForeignKey x) {
      return render(operation) + " NOT VALID;\nALTER TABLE " + q(x.table())
          + " VALIDATE CONSTRAINT " + q(x.fk().name());
    }
    if (operation instanceof org.migrax.ops.AddCheck x) {
      return render(operation) + " NOT VALID;\nALTER TABLE " + q(x.table())
          + " VALIDATE CONSTRAINT " + q(x.check().name());
    }
    return render(operation);
  }

  @Override
  public NameCase unquotedNames() {
    return NameCase.LOWER;
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  /** Emits only the clauses that changed, combined into one ALTER TABLE. */
  @Override
  protected String alterColumn(AlterColumn change) {
    SchemaModel.Column after = change.after();
    String column = " ALTER COLUMN " + q(after.name());
    List<String> clauses = new ArrayList<>();
    if (change.typeChanged() || change.sizeChanged()) {
      clauses.add(column + " TYPE " + renderType(after));
    }
    if (change.nullabilityChanged()) {
      clauses.add(column + (after.nullable() ? " DROP NOT NULL" : " SET NOT NULL"));
    }
    if (change.defaultChanged()) {
      clauses.add(column + (after.defaultValue() == null || after.defaultValue().isBlank()
          ? " DROP DEFAULT" : " SET DEFAULT " + after.defaultValue()));
    }
    if (change.before().identity() != after.identity()) {
      clauses.add(column + (after.identity()
          ? " ADD GENERATED BY DEFAULT AS IDENTITY" : " DROP IDENTITY IF EXISTS"));
    }
    if (clauses.isEmpty()) {
      clauses.add(column + " TYPE " + renderType(after));
    }
    return "ALTER TABLE " + q(change.table()) + String.join(",", clauses);
  }

  /** A session-level advisory lock. */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    long key = lockKey(resource);
    try (PreparedStatement statement =
             connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
      statement.setLong(1, key);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || !result.getBoolean(1)) {
          throw new MigrationLockHeldException("Another Migrax process is applying migrations.");
        }
      }
    }
    return () -> {
      try (PreparedStatement statement =
               connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
        statement.setLong(1, key);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1)) {
            throw new SQLException("Could not release the PostgreSQL migration lock.");
          }
        }
      }
    };
  }

  @Override
  public List<String> syncColumns(String table, String oldColumn, String newColumn) {
    String name = Dialect.syncTriggerName(table, newColumn, 63);
    String a = identifier(oldColumn);
    String b = identifier(newColumn);
    return List.of("CREATE FUNCTION " + name + "() RETURNS trigger AS $$\n"
        + "BEGIN\n"
        + "  IF TG_OP = 'INSERT' THEN\n"
        + "    IF NEW." + b + " IS NULL THEN NEW." + b + " := NEW." + a + "; END IF;\n"
        + "    IF NEW." + a + " IS NULL THEN NEW." + a + " := NEW." + b + "; END IF;\n"
        + "  ELSIF NEW." + b + " IS DISTINCT FROM OLD." + b + " THEN\n"
        + "    NEW." + a + " := NEW." + b + ";\n"
        + "  ELSIF NEW." + a + " IS DISTINCT FROM OLD." + a + " THEN\n"
        + "    NEW." + b + " := NEW." + a + ";\n"
        + "  END IF;\n"
        + "  RETURN NEW;\n"
        + "END\n"
        + "$$ LANGUAGE plpgsql",
        "CREATE TRIGGER " + name + " BEFORE INSERT OR UPDATE ON " + tableName(table)
            + " FOR EACH ROW EXECUTE FUNCTION " + name + "()");
  }

  @Override
  public List<String> dropSyncColumns(String table, String oldColumn, String newColumn) {
    String name = Dialect.syncTriggerName(table, newColumn, 63);
    return List.of("DROP TRIGGER " + name + " ON " + tableName(table),
        "DROP FUNCTION " + name + "()");
  }

  @Override
  public boolean updatableViews() {
    return true;
  }

  /** Renames the key in place: no new key is built, so nothing checks the table's rows. */
  @Override
  public String renameForeignKey(String table, String from, String to) {
    return "ALTER TABLE " + tableName(table) + " RENAME CONSTRAINT " + q(from) + " TO " + q(to);
  }

  @Override
  public boolean largeObjectsAreOids() {
    return true;
  }

  @Override
  public ScratchSetup scratch() {
    return new ScratchSetup("postgres:16-alpine", 5432,
        "jdbc:postgresql://127.0.0.1:{port}/migrax", "migrax", "migrax", 120,
        "POSTGRES_USER=migrax", "POSTGRES_PASSWORD=migrax", "POSTGRES_DB=migrax") {
      @Override
      public boolean hasVersionImages() {
        return true;
      }

      @Override
      public String versionImage(int major, int minor) {
        return major >= 12 ? "postgres:" + major + "-alpine" : null;
      }
    };
  }
}
