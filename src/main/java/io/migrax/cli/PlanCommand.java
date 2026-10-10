package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.diff.Migrations;
import io.migrax.lint.SqlLinter;
import io.migrax.ops.CreateTable;
import io.migrax.ops.Operation;
import io.migrax.verify.TableStats;

import java.io.PrintStream;
import java.nio.file.Files;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** {@code migrax plan}: preview the SQL that generate would write. */
final class PlanCommand implements Command {

  @Override
  public String name() {
    return "plan";
  }

  @Override
  public List<String> aliases() {
    return List.of("sqlmigrate");
  }

  @Override
  public Group group() {
    return Group.EVERYDAY;
  }

  @Override
  public String summary() {
    return "Preview the SQL that generate would write";
  }

  @Override
  public String usage() {
    return "migrax plan [--impact] [--json]";
  }

  @Override
  public String description() {
    return """
        Shows the operations and SQL for current entity changes without writing files.
        --impact adds the estimated size of each affected table from the database.""";
  }

  @Override
  public List<String> options() {
    return List.of("--impact", "--json", "--dialect", "--rename", "--rename-table", "--package",
        "--naming", "--extractor", "--url", "--classpath", "--no-build", "--refresh");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    PrintStream err = context.err();
    Dialect dialect = project.dialect(true, args.flag("--json") ? err : out);
    EntityChanges changes = EntityChanges.read(context, dialect, project.hasUrl(), false);
    if (!Files.exists(project.snapshot()) && !project.hasUrl() && !args.flag("--json")) {
      out.println("No snapshot yet: comparing with an empty schema. 'migrax generate' will "
          + "use the database as the baseline instead.");
    }
    Map<String, TableStats> stats = new HashMap<>();
    if (args.flag("--impact") && !changes.operations().isEmpty()) {
      try (Connection connection = project.connect()) {
        for (Operation operation : changes.operations()) {
          String table = Reports.table(operation);
          if (table != null && !(operation instanceof CreateTable)) {
            stats.computeIfAbsent(table, t -> TableStats.estimate(connection, t));
          }
        }
      }
    }
    String sql = MigrationSql.render(dialect, changes.operations(), null, changes.previous(),
        changes.current());
    List<SqlLinter.Finding> findings = SqlLinter.lint("plan", sql, dialect.id());
    if (args.flag("--json")) {
      List<Object> operations = new ArrayList<>();
      for (Operation operation : changes.operations()) {
        TableStats table = stats.get(Reports.table(operation));
        operations.add(JsonOut.object("kind", operation.kind(),
            "summary", Migrations.summary(operation), "sql", dialect.render(operation),
            "destructive", operation.destructive(), "table", Reports.table(operation),
            "rows", table == null ? null : table.rows(),
            "bytes", table == null ? null : table.bytes()));
      }
      out.println(JsonOut.write(JsonOut.object("dialect", dialect.id(),
          "source", changes.source(), "operations", operations,
          "lint", Reports.findingsJson(findings))));
      return OK;
    }
    if (changes.operations().isEmpty()) {
      out.println("No changes detected.");
      return OK;
    }
    Reports.printOperations(out, changes.operations(), dialect, stats);
    Reports.printFindings(out, findings);
    return OK;
  }
}
