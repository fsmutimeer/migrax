package io.migrax.dialect;

import io.migrax.model.SchemaModel;
import io.migrax.ops.AddColumn;
import io.migrax.ops.AddForeignKey;
import io.migrax.ops.AddIndex;
import io.migrax.ops.AddPrimaryKey;
import io.migrax.ops.AddUnique;
import io.migrax.ops.AlterColumn;
import io.migrax.ops.CreateTable;
import io.migrax.ops.DropColumn;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropIndex;
import io.migrax.ops.DropPrimaryKey;
import io.migrax.ops.DropTable;
import io.migrax.ops.DropUnique;
import io.migrax.ops.Operation;
import io.migrax.ops.RenameColumn;
import io.migrax.ops.RenameTable;
import io.migrax.ops.RunSql;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * SQLite dialect (SQLite 3.35 or newer, with the {@code org.xerial:sqlite-jdbc} driver).
 *
 * <p>SQLite changes little in place: it adds, renames and drops columns, renames tables, and
 * creates and drops indexes. Every other change to an existing table (column types and
 * nullability, primary keys, foreign keys, unique constraints) is made by rebuilding the table:
 * create the new table, copy the rows, drop the old one and rename the new one, which is the
 * procedure SQLite's documentation gives. {@link #prepare} turns those changes into rebuilds.
 *
 * @since 0.2.0
 */
public final class SqliteDialect extends AbstractDialect {
  /** Migration locks for in-memory databases, per URL; shared by every instance. */
  private static final ConcurrentHashMap<String, ReentrantLock> MEMORY_LOCKS =
      new ConcurrentHashMap<>();

  private static final Set<String> RESERVED = Set.of(
      "abort", "action", "after", "analyze", "attach", "autoincrement", "before", "begin",
      "cascade", "cast", "collate", "commit", "conflict", "database", "deferrable", "deferred",
      "detach", "each", "escape", "exclusive", "explain", "fail", "glob", "if", "ignore",
      "immediate", "index", "indexed", "initially", "instead", "isnull", "key", "limit", "match",
      "no", "notnull", "of", "offset", "plan", "pragma", "query", "raise", "recursive", "regexp",
      "reindex", "release", "rename", "replace", "restrict", "rollback", "row", "savepoint",
      "temp", "temporary", "transaction", "trigger", "vacuum", "view", "virtual", "without");

  @Override
  public String id() {
    return "sqlite";
  }

  @Override
  protected Set<String> reservedWords() {
    return RESERVED;
  }

  @Override
  public String quote(String identifier) {
    return "\"" + identifier.replace("\"", "\"\"") + "\"";
  }

  @Override
  protected String logicalType(SchemaModel.Column c, String logical) {
    return switch (logical) {
      case "varchar", "nvarchar" -> "varchar(" + length(c) + ")";
      case "text", "json" -> "text";
      case "clob" -> "clob";
      case "varbinary", "blob" -> "blob";
      // Not "integer": only an integer primary key declared exactly so becomes the row id.
      case "integer" -> "int";
      case "bigint" -> "bigint";
      case "boolean" -> "boolean";
      case "decimal" -> numeric("numeric", c, "numeric(38,2)");
      case "double" -> "double";
      case "float" -> "float";
      case "uuid" -> "blob";
      case "date" -> "date";
      case "time" -> "time";
      case "timestamp" -> "timestamp";
      case "timestamptz" -> "timestamp";
      default -> null;
    };
  }

  /**
   * SQLite stores every integer type the same way (up to 8 bytes), and an identity column must
   * be declared {@code integer}: compare them all as {@code integer}.
   */
  @Override
  public String columnType(SchemaModel.Column column) {
    String type = super.columnType(column);
    return type.toLowerCase(Locale.ROOT).matches("bigint|int|smallint|tinyint|int8|int4|int2")
        ? "integer" : type;
  }

  /**
   * An identity column is SQLite's row id: an {@code integer} primary key gets its value from
   * the row id when none is given. Only the exact type {@code integer} does that.
   */
  @Override
  protected String identityType(SchemaModel.Column c) {
    return "integer";
  }

  /** Foreign keys are part of the table: SQLite can't add them later. */
  @Override
  protected String createTable(SchemaModel.Table t) {
    String table = super.createTable(t);
    if (t.foreignKeys().isEmpty()) {
      return table;
    }
    String keys = t.foreignKeys().stream()
        .map(k -> "CONSTRAINT " + q(k.name()) + " FOREIGN KEY (" + join(k.columns())
            + ") REFERENCES " + q(k.referencedTable()) + " (" + join(k.referencedColumns()) + ")")
        .collect(Collectors.joining(", "));
    return table.substring(0, table.length() - 1) + ", " + keys + ")";
  }

  /** A unique constraint added later is a unique index, which SQLite treats the same. */
  @Override
  public String render(Operation o) {
    if (o instanceof AddUnique x) {
      return "CREATE UNIQUE INDEX " + q(x.name()) + " ON " + q(x.table()) + " (" + q(x.column())
          + ")";
    }
    if (needsRebuild(o) && !(o instanceof AddColumn) && !(o instanceof DropColumn)) {
      // Shown when one change is printed on its own (plan, error reports); migration files
      // get the rebuild from prepare().
      return "-- SQLite rebuilds table " + table(o) + " for this change";
    }
    return super.render(o);
  }

  @Override
  protected String sequenceDefault(String name) {
    return null;
  }

  /** SQLite has no sequences: Hibernate keeps the next value in a one-row table. */
  @Override
  protected String createSequence(SchemaModel.Sequence s) {
    return "CREATE TABLE " + q(s.name()) + " (next_val bigint);\n"
        + "INSERT INTO " + q(s.name()) + " VALUES (" + s.initialValue() + ")";
  }

  @Override
  protected String dropSequence(String name) {
    return "DROP TABLE " + q(name);
  }

  // ------------------------------------------------------------------ table rebuilds

  /**
   * Replaces changes SQLite can't make in place with a rebuild of the table, and moves the
   * foreign keys of new tables into their CREATE TABLE.
   */
  @Override
  public List<Operation> prepare(List<Operation> operations, SchemaModel before,
                                 SchemaModel after) {
    Set<String> created = new LinkedHashSet<>();
    Set<String> dropped = new LinkedHashSet<>();
    for (Operation operation : operations) {
      if (operation instanceof CreateTable x) {
        created.add(x.table().name());
      } else if (operation instanceof DropTable x) {
        dropped.add(x.table());
      }
    }
    Set<String> rebuilt = new LinkedHashSet<>();
    for (Operation operation : operations) {
      String table = table(operation);
      if (table != null && !created.contains(table) && !dropped.contains(table)
          && needsRebuild(operation)) {
        rebuilt.add(table);
      }
    }
    SchemaModel renamed = before;
    for (Operation operation : operations) {
      renamed = applyRename(renamed, operation);
    }
    List<Operation> result = new ArrayList<>();
    for (Operation operation : operations) {
      String table = table(operation);
      if (operation instanceof AddForeignKey && created.contains(table)) {
        continue;
      }
      if (table != null && rebuilt.contains(table)
          && !(operation instanceof RenameTable) && !(operation instanceof RenameColumn)) {
        continue;
      }
      result.add(operation);
    }
    for (String table : rebuilt) {
      SchemaModel.Table target = after.table(table);
      if (target != null) {
        result.add(new RunSql(rebuild(renamed.table(table), target), false));
      }
    }
    return result;
  }

  /** The schema after a rename operation; other operations leave it as it is. */
  private static SchemaModel applyRename(SchemaModel model, Operation operation) {
    List<SchemaModel.Table> tables = new ArrayList<>();
    for (SchemaModel.Table t : model.tables()) {
      if (operation instanceof RenameTable x && t.name().equalsIgnoreCase(x.from())) {
        t = new SchemaModel.Table(x.to(), t.columns(), t.primaryKey(), t.indexes(),
            t.foreignKeys());
      } else if (operation instanceof RenameColumn x && t.name().equalsIgnoreCase(x.table())) {
        List<SchemaModel.Column> columns = t.columns().stream()
            .map(c -> !c.name().equalsIgnoreCase(x.from()) ? c : new SchemaModel.Column(x.to(),
                c.sqlType(), c.nullable(), c.length(), c.precision(), c.scale(),
                c.defaultValue(), c.unique(), c.identity(), c.sequenceName(), c.logicalType()))
            .toList();
        t = new SchemaModel.Table(t.name(), columns, t.primaryKey(), t.indexes(),
            t.foreignKeys());
      }
      tables.add(t);
    }
    return new SchemaModel(tables, model.sequences());
  }

  private static boolean needsRebuild(Operation operation) {
    if (operation instanceof AddColumn x) {
      SchemaModel.Column c = x.column();
      return c.unique() || c.identity() || !c.nullable() && c.defaultValue() == null;
    }
    return operation instanceof AlterColumn || operation instanceof DropColumn
        || operation instanceof AddPrimaryKey || operation instanceof DropPrimaryKey
        || operation instanceof AddForeignKey || operation instanceof DropForeignKey
        || operation instanceof DropUnique;
  }

  /** The table an operation changes, or null for sequences and plain SQL. */
  private static String table(Operation operation) {
    if (operation instanceof CreateTable x) return x.table().name();
    if (operation instanceof DropTable x) return x.table();
    if (operation instanceof AddColumn x) return x.table();
    if (operation instanceof DropColumn x) return x.table();
    if (operation instanceof RenameColumn x) return x.table();
    if (operation instanceof RenameTable x) return x.to();
    if (operation instanceof AlterColumn x) return x.table();
    if (operation instanceof AddPrimaryKey x) return x.table();
    if (operation instanceof DropPrimaryKey x) return x.table();
    if (operation instanceof AddIndex x) return x.table();
    if (operation instanceof DropIndex x) return x.table();
    if (operation instanceof AddForeignKey x) return x.table();
    if (operation instanceof DropForeignKey x) return x.table();
    if (operation instanceof AddUnique x) return x.table();
    if (operation instanceof DropUnique x) return x.table();
    return null;
  }

  /**
   * SQLite's procedure for changing a table: create the new table under a temporary name, copy
   * the columns both versions have, drop the old table, rename the new one and recreate its
   * indexes. Foreign keys of the new table are then checked, so a rebuild that breaks one
   * fails and is rolled back.
   */
  private String rebuild(SchemaModel.Table before, SchemaModel.Table after) {
    String name = after.name();
    String temporary = "migrax_new_" + name;
    List<String> statements = new ArrayList<>();
    statements.add("-- SQLite can't change this table in place: it is rebuilt with its rows.\n"
        + createTable(new SchemaModel.Table(temporary, after.columns(), after.primaryKey(),
            List.of(), after.foreignKeys())));
    if (before != null) {
      List<String> copied = after.columns().stream().map(SchemaModel.Column::name)
          .filter(column -> before.column(column) != null).toList();
      if (!copied.isEmpty()) {
        statements.add("INSERT INTO " + q(temporary) + " (" + join(copied) + ") SELECT "
            + join(copied) + " FROM " + q(name));
      }
    }
    statements.add("DROP TABLE " + q(name));
    statements.add("ALTER TABLE " + q(temporary) + " RENAME TO " + q(name));
    for (SchemaModel.Index index : after.indexes()) {
      statements.add("CREATE " + (index.unique() ? "UNIQUE " : "") + "INDEX " + q(index.name())
          + " ON " + q(name) + " (" + join(index.columns()) + ")");
    }
    if (!after.foreignKeys().isEmpty()) {
      statements.add("CREATE TEMP TABLE migrax_foreign_key_check (broken integer "
          + "CONSTRAINT rebuilt_table_breaks_a_foreign_key CHECK (broken = 0))");
      statements.add("INSERT INTO migrax_foreign_key_check SELECT COUNT(*) FROM "
          + "pragma_foreign_key_check('" + name.replace("'", "''") + "')");
      statements.add("DROP TABLE migrax_foreign_key_check");
    }
    return String.join(";\n", statements);
  }

  // ------------------------------------------------------------------ migration lock

  /**
   * SQLite has no lock that another process can see, so the lock is an operating-system lock
   * on a file next to the database ({@code <database>.migrax-lock}); in-memory databases are
   * only locked within this JVM. While it is held, the connection's foreign key enforcement is
   * off: SQLite can only rebuild a table that others refer to with it off, and it can only be
   * switched outside a transaction.
   */
  @Override
  public AutoCloseable acquireMigrationLock(Connection connection, String resource)
      throws SQLException {
    String url = connection.getMetaData().getURL();
    Path file = databaseFile(url);
    AutoCloseable lock = file == null ? memoryLock(url) : fileLock(file);
    boolean foreignKeys;
    try {
      foreignKeys = pragma(connection, "foreign_keys") == 1;
      if (foreignKeys) {
        setForeignKeys(connection, false);
      }
    } catch (SQLException e) {
      closeQuietly(lock, e);
      throw e;
    }
    return () -> {
      try {
        if (foreignKeys) {
          setForeignKeys(connection, true);
        }
      } finally {
        lock.close();
      }
    };
  }

  /** The database file of a {@code jdbc:sqlite:} URL, or null for an in-memory database. */
  static Path databaseFile(String url) {
    String path = url.substring(url.indexOf(':', "jdbc:".length()) + 1);
    int query = path.indexOf('?');
    String parameters = query < 0 ? "" : path.substring(query + 1);
    path = query < 0 ? path : path.substring(0, query);
    if (path.startsWith("file:")) {
      path = path.substring("file:".length());
    }
    if (path.isBlank() || path.equals(":memory:") || path.startsWith(":resource:")
        || parameters.toLowerCase(Locale.ROOT).contains("mode=memory")) {
      return null;
    }
    return Path.of(path).toAbsolutePath();
  }

  private static AutoCloseable memoryLock(String url) throws SQLException {
    ReentrantLock lock = MEMORY_LOCKS.computeIfAbsent(url, ignored -> new ReentrantLock());
    if (!lock.tryLock()) {
      throw new MigrationLockHeldException("Another Migrax invocation in this JVM is applying migrations.");
    }
    return lock::unlock;
  }

  private static AutoCloseable fileLock(Path database) throws SQLException {
    Path lockFile = database.resolveSibling(database.getFileName() + ".migrax-lock");
    FileChannel channel;
    try {
      channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new SQLException("Could not open the migration lock file " + lockFile + ": "
          + e.getMessage(), e);
    }
    FileLock lock;
    try {
      lock = channel.tryLock();
    } catch (OverlappingFileLockException | IOException e) {
      lock = null;
    }
    if (lock == null) {
      try {
        channel.close();
      } catch (IOException ignored) {
        // The lock is held by someone else either way.
      }
      throw new MigrationLockHeldException("Another Migrax process is applying migrations to " + database
          + ".");
    }
    FileLock held = lock;
    return () -> {
      try {
        held.release();
      } finally {
        channel.close();
      }
    };
  }

  private static int pragma(Connection connection, String name) throws SQLException {
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("PRAGMA " + name)) {
      return result.next() ? result.getInt(1) : 0;
    }
  }

  /** PRAGMA foreign_keys does nothing inside a transaction, so it runs outside one. */
  private static void setForeignKeys(Connection connection, boolean on) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    if (!autoCommit) {
      // Nothing of the migration is pending here: it has committed, or failed and rolled back.
      connection.rollback();
      connection.setAutoCommit(true);
    }
    try (Statement statement = connection.createStatement()) {
      statement.execute("PRAGMA foreign_keys = " + (on ? "ON" : "OFF"));
    } finally {
      if (!autoCommit) {
        connection.setAutoCommit(false);
      }
    }
  }

  private static void closeQuietly(AutoCloseable closeable, Exception failure) {
    try {
      closeable.close();
    } catch (Exception e) {
      failure.addSuppressed(e);
    }
  }
}
