package io.migrax.cli;

import static io.migrax.cli.ExitCode.CHANGES_DETECTED;
import static io.migrax.cli.ExitCode.ERROR;
import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.diff.Migrations;
import io.migrax.ops.Operation;

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
    return List.of("--json", "--package", "--naming", "--extractor", "--classpath", "--no-build",
        "--refresh");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    PrintStream err = context.err();
    Map<String, List<String>> duplicates =
        DuplicateMigrations.unresolved(project, context.migrationRunner());
    Dialect dialect = project.dialect(true, err);
    EntityChanges changes = EntityChanges.read(context, dialect, project.hasUrl(), false);
    if (args.flag("--json")) {
      out.println(JsonOut.write(JsonOut.object(
          "ok", duplicates.isEmpty() && changes.operations().isEmpty(),
          "changes", changes.operations().stream().map(Migrations::summary).toList(),
          "conflicts", new ArrayList<>(duplicates.values()))));
    } else {
      if (!duplicates.isEmpty()) {
        err.println("Migrations share numbers: " + duplicates.values()
            + ". Run 'migrax merge'.");
      }
      if (changes.operations().isEmpty()) {
        if (duplicates.isEmpty()) {
          out.println("OK: entities match the snapshot.");
        }
      } else {
        err.println(changes.operations().size() + " entity change(s) have no migration:");
        for (Operation operation : changes.operations()) {
          err.println("  - " + Migrations.summary(operation));
        }
        err.println("Run 'migrax generate' and commit the result.");
      }
    }
    if (!duplicates.isEmpty()) {
      return ERROR;
    }
    return changes.operations().isEmpty() ? OK : CHANGES_DETECTED;
  }
}
