package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code migrax rollback}: undo applied migrations with their rollback scripts. */
final class RollbackCommand implements Command {

  @Override
  public String name() {
    return "rollback";
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Undo applied migrations with their rollback scripts";
  }

  @Override
  public String usage() {
    return "migrax rollback [--steps n | --to <migration>] [--dry-run] --yes";
  }

  @Override
  public String description() {
    return """
        Runs the rollback script of the newest applied migration (or several) and removes it
        from the history. generate writes rollback scripts to rollback/<file>; Java migrations
        roll back with JavaMigration.rollback. Restores structure, not deleted data.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--steps", "--to", "--dry-run", "--yes", "--schemas", "--lock-timeout",
        "--url", "--user", "--password", "--password-file", "--locations", "--classpath",
        "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    Map<String, Migration> java = new HashMap<>();
    migrations.stream().filter(Migration::isJava).forEach(m -> java.put(m.version(), m));
    MigrationRunner runner = context.migrationRunner();
    try (Connection connection = project.connect()) {
      for (String schema : project.schemasOrDefault()) {
        if (schema != null) {
          Project.useSchema(connection, schema);
          out.println("Schema " + schema + ":");
        }
        List<MigrationRunner.AppliedMigration> applied = runner.applied(connection).stream()
            .filter(m -> !m.version().startsWith("R__")).toList();
        List<MigrationRunner.AppliedMigration> targets = new ArrayList<>();
        String to = args.option("--to");
        if (!Project.blank(to)) {
          String keep = resolveVersion(to, applied.stream()
              .map(MigrationRunner.AppliedMigration::version).toList());
          for (MigrationRunner.AppliedMigration migration : applied) {
            if (MigrationRunner.compareMigrationFilenames(migration.version(), keep) > 0) {
              targets.add(0, migration);
            }
          }
        } else {
          int steps;
          try {
            steps = Integer.parseInt(args.option("--steps", "1"));
          } catch (NumberFormatException e) {
            throw new UsageException("--steps needs a number.", null);
          }
          for (int i = applied.size() - 1; i >= 0 && targets.size() < steps; i--) {
            targets.add(applied.get(i));
          }
        }
        if (targets.isEmpty()) {
          out.println("Nothing to roll back.");
          context.resultList("schemas").add(JsonOut.object("schema", schema, "rolledBack",
              List.of()));
          continue;
        }
        List<Migration> scripts = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (MigrationRunner.AppliedMigration target : targets) {
          if (java.containsKey(target.version())) {
            scripts.add(java.get(target.version()));
            continue;
          }
          Path script = MigrationLoader.rollbackScript(folder, target.version());
          if (script == null) {
            missing.add(target.version());
          } else {
            scripts.add(Migration.ofSql(target.version(), Files.readString(script)));
          }
        }
        if (!missing.isEmpty()) {
          throw new UsageException("No rollback script for " + String.join(", ", missing) + ".",
              "Write one in " + project.display(folder.resolve(MigrationLoader.ROLLBACK_FOLDER))
                  + "/<same file name>, then run rollback again.");
        }
        out.println((args.flag("--dry-run") ? "Would roll back " : "Rolling back ")
            + targets.size() + " migration(s), newest first:");
        targets.forEach(t -> out.println("  " + t.version()));
        List<String> versions = targets.stream()
            .map(MigrationRunner.AppliedMigration::version).toList();
        if (args.flag("--dry-run")) {
          context.resultList("schemas").add(JsonOut.object("schema", schema, "wouldRollBack",
              versions));
          continue;
        }
        if (!args.flag("--yes", "-y")
            && !context.confirm("Roll back now? Data written to removed objects is lost.")) {
          throw new UsageException("Rollback needs confirmation.",
              "Back up the database, then rerun with --yes.");
        }
        for (int i = 0; i < targets.size(); i++) {
          runner.rollback(connection, targets.get(i).version(), scripts.get(i),
              project.runOptions());
        }
        out.println("Rolled back " + targets.size() + " migration(s).");
        context.resultList("schemas").add(JsonOut.object("schema", schema, "rolledBack",
            versions));
      }
    }
    return OK;
  }

  private static String resolveVersion(String wanted, List<String> versions) {
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
    throw new UsageException("Migration '" + wanted + "' is not applied here.",
        "Run 'migrax status' to see applied migrations.");
  }
}
