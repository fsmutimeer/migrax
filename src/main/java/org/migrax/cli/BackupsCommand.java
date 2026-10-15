package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.ops.DropTable;
import org.migrax.plugin.DatabaseSchemaReader;

import java.io.PrintStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/** {@code migrax backups}: list or drop the tables {@code generate --keep-data} kept data in. */
final class BackupsCommand implements Command {

  @Override
  public String name() {
    return "backups";
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "List or drop the backup tables of --keep-data migrations";
  }

  @Override
  public String usage() {
    return "migrax backups [--drop [<migration>] --yes]";
  }

  @Override
  public String description() {
    return """
        Lists the migrax_backup_* tables that migrations generated with --keep-data keep
        dropped data in; their rollback scripts restore it from there. Once you are sure you
        won't roll a migration back, drop its backups: --drop removes them all, or only those
        of one migration (--drop 0005). Asks before dropping; --yes confirms without asking.""";
  }

  @Override
  public boolean jsonResult() {
    return true;
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--drop", "--yes", "--url", "--user", "--password",
        "--password-file", "--schema", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    project.requireUrl();
    Dialect dialect = project.dialect(false, out);
    String migration = args.positionals.size() > 1 ? args.positionals.get(1) : null;
    String prefix = KeepData.PREFIX + (migration == null ? ""
        : migration.toLowerCase(Locale.ROOT) + "_");
    try (Connection connection = project.connect()) {
      String schema = args.option("--schema");
      if (!Project.blank(schema)) {
        Project.useSchema(connection, schema.trim());
      }
      List<String> backups = DatabaseSchemaReader.objectNames(connection,
          Project.blank(schema) ? null : schema.trim(), "TABLE").stream()
          .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
      context.result("backups", backups);
      if (backups.isEmpty()) {
        out.println(migration == null ? "No backup tables." : "No backup tables of migration "
            + migration + ".");
        return OK;
      }
      backups.forEach(name -> out.println("  " + name));
      if (!args.flag("--drop")) {
        out.println(backups.size() + " backup table(s). Rollback scripts restore data from "
            + "them; drop them with 'migrax backups --drop' once you won't roll back.");
        return OK;
      }
      String question = "Drop " + backups.size() + " backup table(s)? Rolling back their "
          + "migrations then restores the structure but not the data.";
      if (!args.flag("--yes", "-y")) {
        if (!context.interactive()) {
          throw new UsageException("Dropping backups deletes the data they keep.",
              "Rerun with --yes to confirm.");
        }
        if (!context.confirm(question)) {
          out.println("Nothing dropped.");
          return OK;
        }
      }
      boolean autoCommit = connection.getAutoCommit();
      connection.setAutoCommit(true);
      Dialect dropping = CleanCommand.inSchema(dialect, Project.blank(schema) ? null
          : schema.trim());
      try (Statement statement = connection.createStatement()) {
        for (String backup : backups) {
          statement.execute(dropping.render(new DropTable(backup)));
        }
      } finally {
        connection.setAutoCommit(autoCommit);
      }
      context.result("dropped", backups);
      out.println("Dropped " + backups.size() + " backup table(s).");
    }
    return OK;
  }
}
