package org.migrax.cli;

import static org.migrax.cli.ExitCode.CHANGES_DETECTED;
import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.diff.Migrations;
import org.migrax.model.SchemaModel;
import org.migrax.ops.Operation;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.plugin.RuntimeJdbc;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

/** {@code migrax diff}: compare the schemas of two databases. */
final class DiffCommand implements Command {

  @Override
  public String name() {
    return "diff";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Compare the schemas of two databases";
  }

  @Override
  public String usage() {
    return "migrax diff <source-url> [--sql] [--json]";
  }

  @Override
  public String description() {
    return """
        Compares the schema of the source database (for example staging) with the
        project's database (for example production; set it with --url) and lists the
        changes that would make the project's database match: tables, columns, types,
        keys, indexes, checks and comments. --sql prints them as one SQL script to review.
        Both must be the same kind of database. Exits with code 2 when they differ.
        The source's user and password come from --source-user and --source-password-file,
        MIGRAX_SOURCE_USER and MIGRAX_SOURCE_PASSWORD, or the URL itself.""";
  }

  @Override
  public List<String> options() {
    return List.of("--sql", "--json", "--source-user", "--source-password-file", "--schema",
        "--url", "--user", "--password", "--password-file", "--classpath", "--no-build",
        "--database");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    if (args.positionals.size() < 2) {
      throw new UsageException("Name the source database: migrax diff <source-url>",
          "For example: migrax diff jdbc:postgresql://staging:5432/shop --url "
              + "jdbc:postgresql://prod:5432/shop");
    }
    String sourceUrl = Project.resolveRelativeDatabasePath(args.positionals.get(1),
        project.root());
    String targetUrl = project.requireUrl().url();
    Dialect dialect = project.dialect(false, args.flag("--json") || args.flag("--sql")
        ? context.err() : out);
    String schema = args.option("--schema");
    SchemaModel source;
    SchemaModel target;
    try (Connection sourceConnection = RuntimeJdbc.connect(project.loader(false), sourceUrl,
             sourceUser(args), sourcePassword(args));
         Connection targetConnection = project.connect()) {
      String sourceKind = sourceConnection.getMetaData().getDatabaseProductName();
      String targetKind = targetConnection.getMetaData().getDatabaseProductName();
      if (!sourceKind.equalsIgnoreCase(targetKind)) {
        throw new UsageException("The databases are of different kinds (" + sourceKind
            + " and " + targetKind + ").",
            "migrax diff compares two databases of the same kind, such as staging and "
                + "production.");
      }
      source = DatabaseSchemaReader.read(sourceConnection, schema);
      target = DatabaseSchemaReader.read(targetConnection, schema);
    }
    List<Operation> operations = context.diffEngine().diff(target, source);
    String sql = MigrationSql.render(dialect, operations, null, target, source);
    if (args.flag("--json")) {
      List<Object> changes = new ArrayList<>();
      for (Operation operation : operations) {
        changes.add(JsonOut.object("kind", operation.kind(),
            "summary", Migrations.summary(operation), "sql", dialect.render(operation),
            "destructive", operation.destructive(), "table", Reports.table(operation)));
      }
      out.println(JsonOut.write(JsonOut.object("source", masked(sourceUrl),
          "target", masked(targetUrl), "dialect", dialect.id(), "changes", changes)));
    } else if (args.flag("--sql")) {
      out.print(operations.isEmpty() ? "-- The databases have the same schema.\n" : sql);
    } else if (operations.isEmpty()) {
      out.println("Same schema: " + masked(targetUrl) + " matches " + masked(sourceUrl) + ".");
    } else {
      out.println("To make " + masked(targetUrl) + " match " + masked(sourceUrl) + ":");
      Reports.printOperations(out, operations, dialect, null);
      out.println("To keep them, save the SQL as a migration: migrax diff <source-url> --sql");
    }
    return operations.isEmpty() ? OK : CHANGES_DETECTED;
  }

  private static String sourceUser(Args args) {
    String user = args.option("--source-user");
    return user != null ? user : System.getenv("MIGRAX_SOURCE_USER");
  }

  private static String sourcePassword(Args args) throws Exception {
    String file = args.option("--source-password-file");
    if (file == null) {
      file = System.getenv("MIGRAX_SOURCE_PASSWORD_FILE");
    }
    if (file != null && !file.isBlank()) {
      return Files.readString(Path.of(file.trim())).replaceAll("\\R+$", "");
    }
    return System.getenv("MIGRAX_SOURCE_PASSWORD");
  }

  /** A JDBC URL without the password it may contain: the same masking as everywhere else. */
  static String masked(String url) {
    return RuntimeJdbc.sanitizeUrl(url);
  }
}
