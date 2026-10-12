package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.sql.Connection;
import java.util.List;

/** {@code migrax baseline}: record existing migrations as applied without running them. */
final class BaselineCommand implements Command {

  @Override
  public String name() {
    return "baseline";
  }

  @Override
  public Group group() {
    return Group.GETTING_STARTED;
  }

  @Override
  public String summary() {
    return "Adopt a database that already has the schema of some migrations";
  }

  @Override
  public String usage() {
    return "migrax baseline <migration> [--dry-run] --yes";
  }

  @Override
  public String description() {
    return """
        For a database whose tables already exist (created by hand, by a script or by
        another tool): records every versioned migration up to and including <migration>
        as applied without running it. 'migrax migrate' then runs only the later ones.
        Refused when the database already has migration history.
        --yes confirms the change to migration history.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--dry-run", "--yes", "--json", "--schemas", "--lock-timeout", "--url",
        "--user", "--password", "--password-file", "--locations", "--java-package",
        "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() != 2) {
      throw new UsageException("Name the last migration the database already has.",
          "Usage: " + usage());
    }
    project.requireUrl();
    List<Migration> migrations = project.migrationsToRun(true);
    String target = MigrateCommand.target(args.positionals.get(1), migrations,
        project.display(project.migrations()));
    List<Migration> existing = migrations.stream()
        .filter(m -> m.kind() == Migration.Kind.VERSIONED)
        .filter(m -> MigrationRunner.compareMigrationFilenames(m.version(), target) <= 0)
        .sorted(MigrationRunner.ORDER).toList();
    boolean dryRun = args.flag("--dry-run");
    if (!dryRun && !args.flag("--yes", "-y")) {
      throw new UsageException("Baseline changes migration history and needs confirmation.",
          "Check that the database really has the schema of these migrations, then rerun "
              + "with --yes (--dry-run lists them).");
    }
    MigrationRunner runner = context.migrationRunner();
    try (Connection connection = project.connect()) {
      for (String schema : project.schemasOrDefault()) {
        if (schema != null) {
          Project.useSchema(connection, schema);
          out.println("Schema " + schema + ":");
        }
        List<String> recorded = runner.statusOf(connection, migrations).stream()
            .filter(row -> row.state() != MigrationRunner.State.PENDING)
            .map(MigrationRunner.MigrationStatus::version).toList();
        if (!recorded.isEmpty()) {
          throw new UsageException("The database already has migration history ("
              + String.join(", ", recorded) + ").",
              "Baseline is only for a database Migrax has not migrated yet. Run 'migrax status'.");
        }
        List<String> versions = existing.stream().map(Migration::version).toList();
        out.println((dryRun ? "Would record " : "Recording ") + versions.size()
            + " migration(s) as applied without running them:");
        versions.forEach(version -> out.println("  " + version));
        if (dryRun) {
          context.resultList("schemas").add(JsonOut.object("schema", schema, "wouldRecord",
              versions));
          continue;
        }
        runner.markApplied(connection, existing);
        context.resultList("schemas").add(JsonOut.object("schema", schema, "recorded",
            versions));
        out.println("Recorded. Run 'migrax migrate' to apply the migrations after " + target
            + ".");
      }
    }
    return OK;
  }
}
