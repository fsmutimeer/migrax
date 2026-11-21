package org.migrax.cli;

import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.importer.Importer;
import org.migrax.model.SchemaModel;

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
    return "Adopt a database managed by another migration tool";
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
        Then it lists the dependencies, build plugins and settings of Flyway or Liquibase
        still in the project, each with its file and line, so you can remove them.
        Afterwards run 'migrax generate' to start managing changes.""";
  }

  @Override
  public List<String> options() {
    return List.of("--table", "--name", "--schema", "--url", "--user", "--password",
        "--password-file", "--locations", "--dialect", "--classpath", "--no-build", "--database");
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
    // Before connecting: reading the entities may rebuild the class loader the driver is in.
    Dialect dialect = source.equals("liquibase") ? project.dialect(false, out) : null;
    SchemaModel entities = dialect == null ? null : project.extract(dialect).model();
    try (Connection connection = project.connect()) {
      switch (source) {
        case "flyway" -> report = Importer.flyway(connection, folder, args.option("--table"));
        case "liquibase" -> {
          String name = args.option("--name");
          if (Project.blank(name)) {
            name = String.format(Locale.ROOT, "%04d_liquibase_baseline",
                MigrationFiles.nextNumber(folder));
          }
          report = Importer.liquibase(connection, dialect, folder, name, args.option("--schema"),
              entities.tables().isEmpty() ? null : entities);
        }
        default -> throw new UsageException("Unknown source '" + source + "'.",
            "Use 'migrax import flyway' or 'migrax import liquibase'.");
      }
    }
    report.notes().forEach(out::println);
    out.println("Recorded " + report.recorded() + " migration(s) as applied.");
    String tool = source.equals("flyway") ? "Flyway" : "Liquibase";
    List<ImportLeftovers.Leftover> leftovers = ImportLeftovers.find(project.root(), source);
    String generate = "run 'migrax generate'; its first run uses the current database as the "
        + "baseline.";
    if (leftovers.isEmpty()) {
      out.println("Found no " + tool + " dependency or setting in the build files and "
          + "application settings under the project folder. Dependencies added by a parent POM "
          + "or a shared build script are not checked.");
      out.println("Next: " + generate);
    } else {
      out.println(tool + " is still in the project. Remove it so that only Migrax migrates "
          + "at startup:");
      leftovers.forEach(leftover -> out.println("  " + leftover));
      out.println("Then " + generate);
    }
    return OK;
  }
}
