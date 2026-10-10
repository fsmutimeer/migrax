package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.runner.Migration;

import java.io.PrintStream;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** {@code migrax repair}: fix migration history after a failed or deleted migration. */
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
    return "Fix migration history after a failed or deleted migration";
  }

  @Override
  public String usage() {
    return "migrax repair <migration>... --action applied|retry|forget --yes";
  }

  @Override
  public String description() {
    return """
        Use only after inspecting the database.
          --action applied  every statement took effect: record the migration as applied
          --action retry    you restored the database: clear the failure so it runs again
          --action forget   you deleted applied migration files on purpose: remove them from
                            the history (the database keeps their changes); several at once
        --yes confirms the change to migration history.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--action", "--yes", "--lock-timeout", "--url", "--user", "--password",
        "--password-file", "--locations", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing the migration to repair.",
          "Usage: " + usage());
    }
    String action = args.option("--action");
    if (Project.blank(action)) {
      throw new UsageException("Missing --action.",
          "Use --action applied (all statements took effect), --action retry (you restored "
              + "the database) or --action forget (you deleted the file on purpose).");
    }
    if (!args.flag("--yes", "-y")) {
      throw new UsageException("Repair changes migration history and needs confirmation.",
          "Inspect and back up the database first, then rerun with --yes.");
    }
    project.requireUrl();
    List<String> wanted = args.positionals.subList(1, args.positionals.size());
    if (action.trim().equalsIgnoreCase("forget")) {
      return forget(context, project, wanted);
    }
    if (wanted.size() > 1) {
      throw new UsageException("Repair one failed migration at a time.",
          "Only --action forget accepts several migrations.");
    }
    Migration migration = null;
    for (Migration candidate : project.migrationsToRun(true)) {
      if (candidate.version().equals(wanted.get(0))
          || candidate.version().equals(wanted.get(0) + ".sql")) {
        migration = candidate;
      }
    }
    if (migration == null) {
      migration = Migration.load(MigrationFiles.find(project, wanted.get(0)));
    }
    try (Connection connection = project.connect()) {
      context.migrationRunner().repair(connection, migration, action, true);
    }
    out.println("Repaired history for " + migration.version() + " (action: "
        + action.trim().toLowerCase(Locale.ROOT) + "). Keep a record of this repair.");
    context.result("action", action.trim().toLowerCase(Locale.ROOT));
    context.result("repaired", List.of(migration.version()));
    return OK;
  }

  /** Removes deleted migration files from the history; refuses files that still exist. */
  private static int forget(CommandContext context, Project project, List<String> wanted)
      throws Exception {
    List<String> versions = new ArrayList<>();
    for (String name : wanted) {
      String version = name.endsWith(".sql") || name.startsWith("V") ? name : name + ".sql";
      for (Migration migration : project.migrationsToRun(true)) {
        if (migration.version().equals(name) || migration.version().equals(version)) {
          throw new UsageException(migration.version() + " still exists in "
              + project.display(project.migrations()) + ".",
              "Forget only migrations whose files you deleted: 'migrate' would run it again. "
                  + "To undo it instead, use 'migrax rollback'.");
        }
      }
      versions.add(version);
    }
    try (Connection connection = project.connect()) {
      for (String version : versions) {
        if (context.migrationRunner().forget(connection, version, true)) {
          context.out().println("Removed " + version + " from the migration history.");
          context.resultList("forgotten").add(version);
        } else {
          context.out().println(version + " is not in the migration history; nothing to forget.");
        }
      }
    }
    context.result("action", "forget");
    context.resultList("forgotten");
    context.out().println("The database keeps the changes these migrations made. "
        + "Run 'migrax status' to check the history.");
    return OK;
  }
}
