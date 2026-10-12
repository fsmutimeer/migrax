package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.lint.SqlLinter;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code migrax migrate}: apply pending migrations to the database. */
final class MigrateCommand implements Command {

  @Override
  public String name() {
    return "migrate";
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Apply pending migrations to the database";
  }

  @Override
  public String usage() {
    return "migrax migrate [--to <migration>] [--dry-run] [--resume] [--schemas a,b]";
  }

  @Override
  public String description() {
    return """
        Applies pending SQL and Java migrations in order, then new or changed repeatable
        R__*.sql migrations, with beforeMigrate/afterMigrate callbacks and ${placeholders}.
        --to applies versioned migrations only up to and including the given one;
        repeatable migrations then wait for the next migrate without --to.
        --dry-run lists and lints pending migrations without changing the database.
        --schemas runs the migrations once per schema (multi-tenant).
        --resume re-runs a failed migration only if it is marked '-- migrax:resume-safe'.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--to", "--env", "--dry-run", "--json", "--resume", "--lock-timeout", "--schemas", "--url",
        "--user", "--password", "--password-file", "--locations", "--java-package", "--classpath",
        "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    project.requireUrl();
    List<Migration> migrations = project.migrationsToRun(true);
    if (migrations.isEmpty() && !hasHistory(context, project)) {
      out.println("No migrations in " + project.displayMigrations() + ". Run 'migrax generate' first.");
      context.result("applied", 0);
      return OK;
    }
    long versioned = migrations.stream().filter(m -> m.kind() == Migration.Kind.VERSIONED).count();
    String target = Project.blank(args.option("--to")) ? null
        : target(args.option("--to"), migrations, project.displayMigrations());
    MigrationRunner.Options options = project.runOptions().withTarget(target);
    Map<String, Migration> byVersion = new HashMap<>();
    migrations.forEach(m -> byVersion.put(m.version(), m));
    MigrationRunner runner = context.migrationRunner();
    try (Connection connection = project.connect()) {
      for (String schema : project.schemasOrDefault()) {
        if (schema != null) {
          Project.useSchema(connection, schema);
          out.println("Schema " + schema + ":");
        }
        if (args.flag("--dry-run")) {
          List<MigrationRunner.MigrationStatus> pending = runner.statusOf(connection, migrations)
              .stream().filter(row -> row.state() == MigrationRunner.State.PENDING
                  || row.state() == MigrationRunner.State.OUTDATED)
              .filter(row -> byVersion.get(row.version()) == null
                  || !options.beyondTarget(byVersion.get(row.version()))).toList();
          context.resultList("schemas").add(JsonOut.object("schema", schema, "wouldApply",
              pending.stream().map(MigrationRunner.MigrationStatus::version).toList()));
          if (pending.isEmpty()) {
            out.println(target != null ? "Nothing to migrate up to " + target + "."
                : "Nothing to migrate: all " + versioned + " migration(s) are applied.");
            continue;
          }
          out.println(pending.size() + " migration(s) would be applied:");
          pending.forEach(row -> out.println("  " + row.version()));
          String dialect = project.dialect(false, out).id();
          for (MigrationRunner.MigrationStatus row : pending) {
            Path file = project.migrationFile(row.version());
            if (file != null) {
              Reports.printFindings(out,
                  SqlLinter.lint(project.display(file), Files.readString(file), dialect));
            }
          }
          continue;
        }
        int applied = runner.migrateAll(connection, migrations, options);
        context.resultList("schemas").add(JsonOut.object("schema", schema, "applied", applied,
            "total", versioned));
        if (target != null) {
          out.println(applied == 0 ? "Nothing to migrate up to " + target + "."
              : "Applied " + applied + " migration(s) up to " + target + ".");
          long waiting = runner.statusOf(connection, migrations).stream()
              .filter(row -> row.state() == MigrationRunner.State.PENDING
                  || row.state() == MigrationRunner.State.OUTDATED).count();
          if (waiting > 0) {
            out.println(waiting + " migration(s) after it stay pending.");
          }
        } else if (applied == 0) {
          out.println("Nothing to migrate: all " + versioned + " migration(s) are applied.");
        } else {
          out.println("Applied " + applied + " migration(s); " + versioned + " total in "
              + project.displayMigrations() + ".");
        }
      }
      return OK;
    }
  }

  /**
   * The versioned migration --to names: its file name (with or without .sql), the start of
   * it, or its version number ({@code 5} for {@code 0005_add_email.sql}).
   */
  static String target(String wanted, List<Migration> migrations, String folder) {
    List<String> versions = migrations.stream()
        .filter(m -> m.kind() == Migration.Kind.VERSIONED).sorted(MigrationRunner.ORDER)
        .map(Migration::version).toList();
    for (String version : versions) {
      if (version.equals(wanted) || version.equals(wanted + ".sql")) {
        return version;
      }
    }
    for (String version : versions) {
      if (version.startsWith(wanted)) {
        return version;
      }
    }
    List<java.math.BigInteger> number = MigrationRunner.versionParts(wanted);
    for (String version : versions) {
      if (!number.isEmpty() && MigrationRunner.versionParts(version).equals(number)) {
        return version;
      }
    }
    throw new UsageException("No migration '" + wanted + "' in " + folder + ".",
        "Run 'migrax status' to see the migrations.");
  }

  /**
   * Whether the database recorded applied migrations. With an empty folder that means the files
   * were deleted, and migrating must report them instead of saying there is nothing to do.
   */
  private static boolean hasHistory(CommandContext context, Project project) throws Exception {
    try (Connection connection = project.connect()) {
      return !context.migrationRunner().applied(connection).isEmpty();
    }
  }
}
