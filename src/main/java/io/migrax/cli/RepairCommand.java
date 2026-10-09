package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.runner.Migration;

import java.io.PrintStream;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

/** {@code migrax repair}: fix migration history after a failed migration. */
final class RepairCommand implements Command {

  @Override
  public String name() {
    return "repair";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Fix migration history after a failed migration";
  }

  @Override
  public String usage() {
    return "migrax repair <migration> --action applied|retry --yes";
  }

  @Override
  public String description() {
    return """
        Use only after inspecting the database.
          --action applied  every statement took effect: record the migration as applied
          --action retry    you restored the database: clear the failure so it runs again
        --yes confirms the change to migration history.""";
  }

  @Override
  public List<String> options() {
    return List.of("--action", "--yes", "--url", "--user", "--password", "--locations",
        "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration to repair.",
          "Usage: migrax repair <migration> --action applied|retry --yes");
    }
    String action = args.option("--action");
    if (Project.blank(action)) {
      throw new UsageException("Missing --action.",
          "Use --action applied (all statements took effect) or --action retry "
              + "(you restored the database).");
    }
    if (!args.flag("--yes", "-y")) {
      throw new UsageException("Repair changes migration history and needs confirmation.",
          "Inspect and back up the database first, then rerun with --yes.");
    }
    project.requireUrl();
    String wanted = args.positionals.get(1);
    Migration migration = null;
    for (Migration candidate : project.migrationsToRun(true)) {
      if (candidate.version().equals(wanted) || candidate.version().equals(wanted + ".sql")) {
        migration = candidate;
      }
    }
    if (migration == null) {
      migration = Migration.load(MigrationFiles.find(project, wanted));
    }
    try (Connection connection = project.connect()) {
      context.migrationRunner().repair(connection, migration, action, true);
    }
    out.println("Repaired history for " + migration.version() + " (action: "
        + action.trim().toLowerCase(Locale.ROOT) + "). Keep a record of this repair.");
    return OK;
  }
}
