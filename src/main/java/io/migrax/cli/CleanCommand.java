package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.SqliteDialect;
import io.migrax.model.SchemaModel;
import io.migrax.ops.DropForeignKey;
import io.migrax.ops.DropSequence;
import io.migrax.ops.DropTable;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.runner.DatabaseMigrationLock;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code migrax clean}: drop every table, view and sequence, for development databases. */
final class CleanCommand implements Command {

  /** CockroachDB's migration lock lives in this table; it goes last, after the lock is free. */
  private static final String LOCK_TABLE = "migrax_lock";

  @Override
  public String name() {
    return "clean";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Drop every table, view and sequence (development databases)";
  }

  @Override
  public String usage() {
    return "migrax clean [--dry-run] [--yes]";
  }

  @Override
  public String description() {
    return """
        Empties the database so 'migrax migrate' can rebuild it from the migrations: drops every
        table (the migration history too), view and sequence in the configured schema. Data is
        deleted and can't be restored. Migration files and the snapshot are not touched.
        Asks before dropping; --yes confirms without asking. --dry-run lists what would be
        dropped. Set MIGRAX_CLEAN_DISABLED=true on servers where clean must never run.""";
  }

  @Override
  public List<String> options() {
    return List.of("--dry-run", "--yes", "--lock-timeout", "--url", "--user", "--password",
        "--password-file", "--schema", "--schemas", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (disabled()) {
      throw new UsageException("clean is disabled here (MIGRAX_CLEAN_DISABLED is true).",
          "It drops everything in the database; unset MIGRAX_CLEAN_DISABLED only on a "
              + "development or test database.");
    }
    project.requireUrl();
    Dialect dialect = project.dialect(false, out);
    String url = project.credentials().url();
    try (Connection connection = project.connect()) {
      for (String schema : schemas(project, args)) {
        if (schema != null) {
          Project.useSchema(connection, schema);
        }
        String where = schema == null ? url : "schema " + schema + " of " + url;
        Objects objects = Objects.read(connection, schema);
        if (objects.isEmpty()) {
          out.println("Nothing to clean in " + where + ".");
          continue;
        }
        if (args.flag("--dry-run")) {
          out.println("Would drop from " + where + ":");
          objects.print(out);
          continue;
        }
        String question = "Drop " + objects.count() + " from " + where
            + "? This deletes all their data.";
        if (!args.flag("--yes", "-y")) {
          if (!context.interactive()) {
            throw new UsageException("clean deletes everything in " + where + ".",
                "Check it is the right database (migrax clean --dry-run), then rerun with --yes.");
          }
          objects.print(out);
          if (!context.confirm(question)) {
            out.println("Nothing dropped.");
            return OK;
          }
        }
        drop(connection, dialect, objects, schema, project.lockTimeout());
        out.println("Dropped " + objects.count() + " from " + where + ".");
      }
    }
    if (!args.flag("--dry-run")) {
      out.println("Run 'migrax migrate' to rebuild the schema from the migrations.");
    }
    return OK;
  }

  private static boolean disabled() {
    return "true".equalsIgnoreCase(System.getenv("MIGRAX_CLEAN_DISABLED"))
        || "true".equalsIgnoreCase(System.getProperty("migrax.cleanDisabled"));
  }

  private static List<String> schemas(Project project, Args args) throws Exception {
    String schema = args.option("--schema");
    if (!Project.blank(schema)) {
      List<String> one = new ArrayList<>();
      one.add(schema.trim());
      return one;
    }
    return project.schemasOrDefault();
  }

  /**
   * Drops views, then foreign keys (so tables can go in any order), tables and the sequences
   * that are left, with the migration lock held so no migration runs meanwhile.
   */
  private static void drop(Connection connection, Dialect dialect, Objects objects,
                           String schema, java.time.Duration lockTimeout) throws Exception {
    boolean autoCommit = connection.getAutoCommit();
    connection.setAutoCommit(true);
    try {
      try (AutoCloseable lock = DatabaseMigrationLock.acquire(connection, lockTimeout)) {
        // The lock may have switched auto-commit off for its own work.
        connection.setAutoCommit(true);
        dropViews(connection, dialect, objects.views);
        // SQLite drops tables in any order while the lock has foreign keys switched off, and it
        // can't drop a foreign key on its own.
        if (!(dialect instanceof SqliteDialect)) {
          for (SchemaModel.Table table : objects.schema.tables()) {
            for (SchemaModel.ForeignKey key : table.foreignKeys()) {
              execute(connection, dialect.render(new DropForeignKey(table.name(), key.name())));
            }
          }
        }
        for (String table : objects.tables) {
          if (!table.equalsIgnoreCase(LOCK_TABLE)) {
            execute(connection, dialect.render(new DropTable(table)));
          }
        }
        // Sequences behind identity columns went with their tables.
        for (String sequence : DatabaseSchemaReader.sequencesIn(connection, schema)) {
          execute(connection, dialect.render(new DropSequence(sequence)));
        }
      }
      if (objects.tables.stream().anyMatch(t -> t.equalsIgnoreCase(LOCK_TABLE))) {
        execute(connection, dialect.render(new DropTable(LOCK_TABLE)));
      }
    } finally {
      connection.setAutoCommit(autoCommit);
    }
  }

  /** Views can depend on each other: drop what can go, then try the rest again. */
  private static void dropViews(Connection connection, Dialect dialect, List<String> views)
      throws SQLException {
    List<String> pending = new ArrayList<>(views);
    while (!pending.isEmpty()) {
      SQLException last = null;
      List<String> failed = new ArrayList<>();
      for (String view : pending) {
        try {
          execute(connection, "DROP VIEW " + dialect.quote(view));
        } catch (SQLException e) {
          failed.add(view);
          last = e;
        }
      }
      if (failed.size() == pending.size()) {
        throw last;
      }
      pending = failed;
    }
  }

  private static void execute(Connection connection, String sql) throws SQLException {
    for (String statement : sql.split(";\n")) {
      try (Statement run = connection.createStatement()) {
        run.execute(statement);
      }
    }
  }

  /** What clean finds in one schema. */
  private record Objects(List<String> tables, List<String> views, List<String> sequences,
                         SchemaModel schema) {
    static Objects read(Connection connection, String schema) throws SQLException {
      return new Objects(DatabaseSchemaReader.objectNames(connection, schema, "TABLE"),
          DatabaseSchemaReader.objectNames(connection, schema, "VIEW"),
          DatabaseSchemaReader.sequencesIn(connection, schema),
          DatabaseSchemaReader.read(connection, schema));
    }

    boolean isEmpty() {
      return tables.isEmpty() && views.isEmpty() && sequences.isEmpty();
    }

    String count() {
      List<String> parts = new ArrayList<>();
      parts.add(tables.size() + " table(s)");
      if (!views.isEmpty()) {
        parts.add(views.size() + " view(s)");
      }
      if (!sequences.isEmpty()) {
        parts.add(sequences.size() + " sequence(s)");
      }
      return String.join(", ", parts);
    }

    void print(PrintStream out) {
      tables.forEach(t -> out.println("  table    " + t));
      views.forEach(v -> out.println("  view     " + v));
      sequences.forEach(s -> out.println("  sequence " + s.toLowerCase(Locale.ROOT)));
    }
  }
}
