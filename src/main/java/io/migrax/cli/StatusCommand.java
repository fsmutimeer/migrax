package io.migrax.cli;

import static io.migrax.cli.ExitCode.ERROR;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.plugin.RuntimeJdbc;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code migrax status}: show applied and pending migrations. */
final class StatusCommand implements Command {

  @Override
  public String name() {
    return "status";
  }

  @Override
  public List<String> aliases() {
    return List.of("showmigrations");
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Show applied and pending migrations";
  }

  @Override
  public String usage() {
    return "migrax status [--json]";
  }

  @Override
  public String description() {
    return """
        Lists migrations as applied [X], pending [ ] or a problem [!], without changing the
        database. Exits with code 1 when a migration failed, changed or is missing.""";
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--schemas", "--url", "--user", "--password", "--password-file",
        "--locations", "--java-package", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    PrintStream err = context.err();
    project.requireUrl();
    Path folder = project.migrations();
    List<Migration> migrations = project.migrationsToRun(true);
    boolean problems = false;
    List<Object> json = new ArrayList<>();
    try (Connection connection = project.connect()) {
      for (String schema : project.schemasOrDefault()) {
        if (schema != null) {
          Project.useSchema(connection, schema);
        }
        List<MigrationRunner.MigrationStatus> rows =
            context.migrationRunner().statusOf(connection, migrations);
        problems |= rows.stream().anyMatch(row -> row.state() == MigrationRunner.State.FAILED
            || row.state() == MigrationRunner.State.CHANGED
            || row.state() == MigrationRunner.State.MISSING);
        if (args.flag("--json")) {
          json.add(JsonOut.object("schema", schema, "migrations", rows.stream().map(row ->
              (Object) JsonOut.object("version", row.version(), "state", row.state(),
                  "detail", row.detail())).toList()));
          continue;
        }
        out.println("Migrations in " + project.display(folder) + " for "
            + RuntimeJdbc.sanitizeUrl(project.credentials().url())
            + (schema == null ? "" : ", schema " + schema));
        if (rows.isEmpty()) {
          out.println("  (none) Run 'migrax generate' to create the first migration.");
          continue;
        }
        int width = rows.stream().mapToInt(row -> row.version().length()).max().orElse(10);
        Map<MigrationRunner.State, Integer> counts = new LinkedHashMap<>();
        for (MigrationRunner.MigrationStatus row : rows) {
          counts.merge(row.state(), 1, Integer::sum);
          String mark = switch (row.state()) {
            case APPLIED -> "[X]";
            case PENDING -> "[ ]";
            case OUTDATED -> "[~]";
            default -> "[!]";
          };
          String detail = switch (row.state()) {
            case APPLIED, OUTDATED -> row.detail();
            case PENDING -> row.detail().isEmpty() ? "pending" : "pending: " + row.detail();
            default -> row.state().name() + ": " + row.detail();
          };
          out.printf("  %s %-" + width + "s  %s%n", mark, row.version(), detail);
        }
        List<String> summary = new ArrayList<>();
        counts.forEach((state, count) ->
            summary.add(count + " " + state.name().toLowerCase(Locale.ROOT)));
        out.println(String.join(", ", summary) + ".");
      }
    }
    if (args.flag("--json")) {
      out.println(JsonOut.write(json.size() == 1 ? json.get(0) : json));
    } else if (problems) {
      err.println("Some migrations need attention. See 'migrax help repair'.");
    }
    return problems ? ERROR : OK;
  }
}
