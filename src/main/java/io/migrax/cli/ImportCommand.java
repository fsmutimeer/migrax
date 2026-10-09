package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.importer.Importer;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;

/** {@code migrax import}: adopt a database managed by Flyway or Liquibase. */
final class ImportCommand implements Command {

  @Override
  public String name() {
    return "import";
  }

  @Override
  public Group group() {
    return Group.GETTING_STARTED;
  }

  @Override
  public String summary() {
    return "Adopt a database managed by Flyway or Liquibase";
  }

  @Override
  public String usage() {
    return "migrax import flyway|liquibase";
  }

  @Override
  public String description() {
    return """
        flyway: records every migration in flyway_schema_history as applied, so Migrax
        continues where Flyway stopped; V*__ and R__ files keep working.
        liquibase: writes a baseline migration with the current schema and records it.
        Afterwards run 'migrax generate' to start managing changes, and remove Flyway or
        Liquibase from the project.""";
  }

  @Override
  public List<String> options() {
    return List.of("--table", "--name", "--schema", "--url", "--user", "--password", "--locations",
        "--dialect", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Missing what to import from.",
          "Usage: migrax import flyway  or  migrax import liquibase");
    }
    String source = args.positionals.get(1).toLowerCase(Locale.ROOT);
    project.requireUrl();
    Path folder = project.migrations();
    Importer.Report report;
    try (Connection connection = project.connect()) {
      switch (source) {
        case "flyway" -> report = Importer.flyway(connection, folder, args.option("--table"));
        case "liquibase" -> {
          Dialect dialect = project.dialect(false, out);
          String name = args.option("--name");
          if (Project.blank(name)) {
            name = String.format(Locale.ROOT, "%04d_liquibase_baseline",
                MigrationFiles.nextNumber(folder));
          }
          report = Importer.liquibase(connection, dialect, folder, name, args.option("--schema"));
        }
        default -> throw new UsageException("Unknown source '" + source + "'.",
            "Use 'migrax import flyway' or 'migrax import liquibase'.");
      }
    }
    report.notes().forEach(out::println);
    out.println("Recorded " + report.recorded() + " migration(s) as applied.");
    out.println("Next: disable " + (source.equals("flyway") ? "Flyway (spring.flyway.enabled=false)"
        : "Liquibase (spring.liquibase.enabled=false)") + ", then run 'migrax generate'; its "
        + "first run uses the current database as the baseline.");
    return OK;
  }
}
