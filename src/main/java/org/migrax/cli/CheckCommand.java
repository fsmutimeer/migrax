package org.migrax.cli;

import static org.migrax.cli.ExitCode.CHANGES_DETECTED;
import static org.migrax.cli.ExitCode.ERROR;
import static org.migrax.cli.ExitCode.OK;

import org.migrax.dialect.Dialect;
import org.migrax.diff.Migrations;
import org.migrax.ops.Operation;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code migrax check}: fail when entities changed without a migration (for CI). */
final class CheckCommand implements Command {

  @Override
  public String name() {
    return "check";
  }

  @Override
  public Group group() {
    return Group.SAFETY;
  }

  @Override
  public String summary() {
    return "Fail when entities changed without a migration (for CI)";
  }

  @Override
  public String usage() {
    return "migrax check [--json]";
  }

  @Override
  public String description() {
    return """
        Exits with code 2 when the entities differ from .migrax/snapshot.json, and with 1 when
        two migrations share a number (run 'migrax merge').""";
  }

  @Override
  public List<String> options() {
    return List.of("--json", "--package", "--naming", "--extractor", "--schema-file",
        "--scratch-url", "--classpath", "--no-build", "--refresh", "--database");
  }

  /** What check found: its exit code and its --json object. */
  record Result(int code, Map<String, Object> json) {}

  @Override
  public int run(CommandContext context) throws Exception {
    Result result = check(context, context.args().flag("--json"));
    if (context.args().flag("--json")) {
      context.out().println(JsonOut.write(result.json()));
    }
    return result.code();
  }

  /**
   * Compares the entities with the snapshot; prints the text report unless {@code json}. Also
   * used by {@code status --check}.
   */
  static Result check(CommandContext context, boolean json) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    PrintStream err = context.err();
    Map<String, List<String>> duplicates =
        DuplicateMigrations.unresolved(project, context.migrationRunner());
    Dialect dialect = project.dialect(true, err);
    EntityChanges changes = EntityChanges.read(context, dialect, project.hasUrl(), false);
    Map<String, Object> result = JsonOut.object(
        "ok", duplicates.isEmpty() && changes.operations().isEmpty(),
        "changes", changes.operations().stream().map(Migrations::summary).toList(),
        "operations", changes.operations().stream()
            .map(o -> Reports.operationJson(o, changes.entities())).toList(),
        "conflicts", new ArrayList<>(duplicates.values()));
    if (!json) {
      if (!duplicates.isEmpty()) {
        err.println("Migrations share numbers: " + duplicates.values() + ". "
            + DuplicateMigrations.advice(duplicates));
      }
      boolean schemaFile = project.schemaFile() != null;
      if (changes.operations().isEmpty()) {
        if (duplicates.isEmpty()) {
          out.println(schemaFile ? "OK: " + changes.source() + " matches the snapshot."
              : "OK: entities match the snapshot.");
        }
      } else {
        err.println(changes.operations().size() + (schemaFile
            ? " change(s) in " + changes.source() + " have no migration:"
            : " entity change(s) have no migration:"));
        for (Operation operation : changes.operations()) {
          err.println("  - " + Migrations.summary(operation));
        }
        err.println("Run 'migrax generate' and commit the result.");
      }
    }
    int code = !duplicates.isEmpty() ? ERROR
        : changes.operations().isEmpty() ? OK : CHANGES_DETECTED;
    return new Result(code, result);
  }
}
