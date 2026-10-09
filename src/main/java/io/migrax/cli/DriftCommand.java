package io.migrax.cli;

import static io.migrax.cli.ExitCode.CHANGES_DETECTED;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.SchemaModel;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.runner.MigrationRunner;
import io.migrax.verify.SchemaComparator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.List;

/** {@code migrax drift}: compare the live database with the expected schema. */
final class DriftCommand implements Command {

  @Override
  public String name() {
    return "drift";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Compare the live database with the expected schema";
  }

  @Override
  public String usage() {
    return "migrax drift [--entities] [--json]";
  }

  @Override
  public String description() {
    return """
        Reads the database schema and reports manual changes: missing or extra tables,
        columns, keys and unique constraints, nullability and type differences. Compares
        with the snapshot (what migrations produce) or, with --entities, the entities.
        Exits with code 2 when drift is found.""";
  }

  @Override
  public List<String> options() {
    return List.of("--entities", "--json", "--schema", "--url", "--user", "--password", "--package",
        "--naming", "--extractor", "--classpath", "--no-build");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    project.requireUrl();
    Dialect dialect = project.dialect(false, out);
    SchemaModel expected;
    String against;
    if (args.flag("--entities")) {
      expected = project.extract(dialect).model();
      against = "entities";
    } else {
      if (!Files.exists(project.snapshot())) {
        throw new UsageException("There is no snapshot to compare with.",
            "Run 'migrax generate' first, or pass --entities.");
      }
      expected = SnapshotStore.load(project.snapshot());
      against = "snapshot";
    }
    List<SchemaComparator.Difference> differences;
    long pending;
    try (Connection connection = project.connect()) {
      differences = SchemaComparator.compare(expected,
          DatabaseSchemaReader.read(connection, args.option("--schema")), dialect.id());
      pending = context.migrationRunner().statusOf(connection, project.migrationsToRun(true))
          .stream().filter(r -> r.state() == MigrationRunner.State.PENDING).count();
    }
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object("against", against, "pending", pending,
          "differences", differences.stream().map(d -> (Object) JsonOut.object(
              "kind", d.kind(), "object", d.object(), "detail", d.detail())).toList())));
    } else {
      if (pending > 0) {
        out.println(pending + " migration(s) are pending; differences they cover are expected.");
      }
      if (differences.isEmpty()) {
        out.println("No drift: the database matches the " + against + ".");
      } else {
        out.println(differences.size() + " difference(s) between the database and the "
            + against + ":");
        differences.forEach(d -> out.println("  - " + d));
        out.println("Someone changed the database outside migrations. Write a migration for "
            + "intended changes ('migrax new <name>'), or revert them.");
      }
    }
    return differences.isEmpty() ? OK : CHANGES_DETECTED;
  }
}
