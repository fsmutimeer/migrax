package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.lint.SqlLinter;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

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
    return "migrax migrate [--dry-run] [--resume] [--schemas a,b]";
  }

  @Override
  public String description() {
    return """
        Applies pending SQL and Java migrations in order, then new or changed repeatable
        R__*.sql migrations, with beforeMigrate/afterMigrate callbacks and ${placeholders}.
        --dry-run lists and lints pending migrations without changing the database.
        --schemas runs the migrations once per schema (multi-tenant).
        --resume re-runs a failed migration only if it is marked '-- migrax:resume-safe'.""";
  }

  @Override
  public List<String> options() {
    return List.of("--dry-run", "--resume", "--schemas", "--url", "--user", "--password",
        "--locations", "--java-package", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    if (migrations.isEmpty() && !hasHistory(context, project)) {
      out.println("No migrations in " + project.display(folder) + ". Run 'migrax generate' first.");
      return OK;
    }
    long versioned = migrations.stream().filter(m -> m.kind() == Migration.Kind.VERSIONED).count();
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
                  || row.state() == MigrationRunner.State.OUTDATED).toList();
          if (pending.isEmpty()) {
            out.println("Nothing to migrate: all " + versioned + " migration(s) are applied.");
            continue;
          }
          out.println(pending.size() + " migration(s) would be applied:");
          pending.forEach(row -> out.println("  " + row.version()));
          String dialect = project.dialect(false, out).id();
          for (MigrationRunner.MigrationStatus row : pending) {
            Path file = folder.resolve(row.version());
            if (Files.isRegularFile(file)) {
              Reports.printFindings(out,
                  SqlLinter.lint(project.display(file), Files.readString(file), dialect));
            }
          }
          continue;
        }
        int applied = runner.migrateAll(connection, migrations, project.runOptions());
        if (applied == 0) {
          out.println("Nothing to migrate: all " + versioned + " migration(s) are applied.");
        } else {
          out.println("Applied " + applied + " migration(s); " + versioned + " total in "
              + project.display(folder) + ".");
        }
      }
      return OK;
    }
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
