package io.migrax.cli;

import static io.migrax.cli.ExitCode.ERROR;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.lint.SqlLinter;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.util.Log;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** {@code migrax lint}: find locking and risky SQL in migrations. */
final class LintCommand implements Command {

  @Override
  public String name() {
    return "lint";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Find locking and risky SQL in migrations";
  }

  @Override
  public String usage() {
    return "migrax lint [files...] [--all] [--strict] [--json]";
  }

  @Override
  public String description() {
    return """
        Checks pending migrations (or the given files, or --all) for statements that block
        tables, fail on existing data, or break running application instances, and suggests
        safe alternatives. Exits with 1 on errors, or on warnings with --strict.
        Silence a finding with '-- migrax:lint-ignore MX001' before the statement.""";
  }

  @Override
  public List<String> options() {
    return List.of("--all", "--strict", "--json", "--dialect", "--url", "--locations");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    PrintStream err = context.err();
    String dialect = project.dialect(true, args.flag("--json") ? err : out).id();
    Path folder = project.migrations();
    List<Path> files = new ArrayList<>();
    if (args.positionals.size() > 1) {
      for (String name : args.positionals.subList(1, args.positionals.size())) {
        files.add(MigrationFiles.find(project, name));
      }
    } else {
      List<Path> all = MigrationLoader.sqlFiles(folder);
      if (args.flag("--all") || !project.hasUrl()) {
        files.addAll(all);
      } else {
        try (Connection connection = project.connect()) {
          Set<String> pending = new HashSet<>();
          context.migrationRunner().status(connection, all).stream()
              .filter(r -> r.state() == MigrationRunner.State.PENDING
                  || r.state() == MigrationRunner.State.OUTDATED)
              .forEach(r -> pending.add(r.version()));
          all.stream().filter(f -> pending.contains(f.getFileName().toString()))
              .forEach(files::add);
        } catch (Exception e) {
          Log.warn("Could not read pending migrations ({}); linting all files.",
              Errors.describe(e));
          files.addAll(all);
        }
      }
    }
    List<SqlLinter.Finding> findings = new ArrayList<>();
    for (Path file : files) {
      findings.addAll(SqlLinter.lint(project.display(file), Files.readString(file), dialect));
    }
    boolean errors = findings.stream().anyMatch(f -> f.severity() == SqlLinter.Severity.ERROR);
    boolean warnings = findings.stream().anyMatch(f -> f.severity() == SqlLinter.Severity.WARNING);
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object("files", files.size(),
          "findings", Reports.findingsJson(findings))));
    } else {
      Reports.printFindings(out, findings);
      out.println(files.size() + " file(s) checked, " + findings.size() + " finding(s).");
    }
    return errors || warnings && args.flag("--strict") ? ERROR : OK;
  }
}
