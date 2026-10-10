package io.migrax.cli;

import static io.migrax.cli.ExitCode.OK;

import io.migrax.dialect.Dialect;
import io.migrax.diff.SnapshotStore;
import io.migrax.model.SchemaModel;
import io.migrax.ops.Operation;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.runner.SqlScript;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@code migrax squash}: combine old migrations into one. */
final class SquashCommand implements Command {

  @Override
  public String name() {
    return "squash";
  }

  @Override
  public List<String> aliases() {
    return List.of("squashmigrations");
  }

  @Override
  public Group group() {
    return Group.MAINTENANCE;
  }

  @Override
  public String summary() {
    return "Combine old migrations into one";
  }

  @Override
  public String usage() {
    return "migrax squash --to <migration> [--optimize]";
  }

  @Override
  public String description() {
    return """
        Writes one migration that replaces all migrations up to --to. Databases that already
        applied them record it without running it; new databases run just the squashed file.
        --optimize writes only the resulting schema (data statements are dropped).
        Delete the old files once every environment has run migrate.""";
  }

  @Override
  public List<String> options() {
    return List.of("--to", "--name", "--optimize", "--dialect", "--locations");
  }

  @Override
  public int run(CommandContext context) throws Exception {
    Project project = context.project();
    Args args = context.args();
    PrintStream out = context.out();
    String to = args.option("--to");
    if (Project.blank(to)) {
      throw new UsageException("Missing --to.", "Usage: migrax squash --to <migration>");
    }
    Path folder = project.migrations();
    List<Path> files = MigrationLoader.sqlFiles(folder).stream()
        .filter(p -> {
          String name = p.getFileName().toString();
          return !name.startsWith("R__") && !Migration.CALLBACKS.contains(name);
        }).toList();
    String target = resolveFile(to, files.stream().map(p -> p.getFileName().toString()).toList());
    List<Path> range = files.stream().filter(p -> MigrationRunner.compareMigrationFilenames(
        p.getFileName().toString(), target) <= 0).toList();
    if (range.size() < 2) {
      throw new UsageException("Nothing to squash: " + target + " is the first migration.",
          "Pick a later migration with --to.");
    }
    List<String> replaces = new ArrayList<>();
    StringBuilder body = new StringBuilder();
    for (Path file : range) {
      String name = file.getFileName().toString();
      String sql = Files.readString(file);
      if (SqlScript.hasDirective(sql, "no-transaction")) {
        throw new UsageException(name + " must run outside a transaction, so it cannot be "
            + "squashed.", "Squash up to the migration before it.");
      }
      replaces.addAll(SqlScript.directiveValues(sql, "replaces"));
      replaces.add(name);
      body.append("\n-- ---- ").append(name).append(" ----\n");
      for (String line : sql.split("\n", -1)) {
        if (!line.trim().toLowerCase(Locale.ROOT).startsWith("-- migrax:replaces")) {
          body.append(line).append("\n");
        }
      }
    }
    if (args.flag("--optimize")) {
      Path history = SnapshotStore.historySnapshot(project.root(), target);
      if (!Files.exists(history)) {
        throw new UsageException("--optimize needs " + project.display(history) + ".",
            "It is written by 'migrax generate'. Squash without --optimize instead.");
      }
      Dialect dialect = project.dialect(false, out);
      body.setLength(0);
      body.append("\n-- Resulting schema only; data statements of the replaced migrations are "
          + "not included.\n");
      SchemaModel schema = SnapshotStore.load(history);
      for (Operation operation : dialect.prepare(
          context.diffEngine().diff(SchemaModel.empty(), schema), SchemaModel.empty(), schema)) {
        body.append(dialect.render(operation)).append(";\n");
      }
    }
    Pattern leading = Pattern.compile("^[vV]?(\\d+)");
    Matcher firstNumber = leading.matcher(range.get(0).getFileName().toString());
    String first = firstNumber.find() ? firstNumber.group(1) : "0";
    Matcher number = leading.matcher(target);
    String prefix = number.find() ? number.group(1) : "0";
    String name = Project.blank(args.option("--name"))
        ? prefix + "_squashed_" + first + "_" + prefix
        : args.option("--name").replaceFirst("\\.sql$", "");
    Path file = folder.resolve(name + ".sql");
    if (Files.exists(file)) {
      throw new UsageException("Migration already exists: " + project.display(file), null);
    }
    List<String> unique = new ArrayList<>(new LinkedHashSet<>(replaces));
    Files.writeString(file, "-- Squashed by Migrax " + Version.current() + " from "
        + unique.size() + " migrations.\n-- migrax:replaces " + String.join(",", unique)
        + "\n" + body);

    List<String> rollbackParts = new ArrayList<>();
    for (int i = range.size() - 1; i >= 0; i--) {
      Path script = MigrationLoader.rollbackScript(folder, range.get(i).getFileName().toString());
      if (script == null) {
        rollbackParts = null;
        break;
      }
      rollbackParts.add("-- ---- rollback of " + range.get(i).getFileName() + " ----\n"
          + Files.readString(script));
    }
    if (rollbackParts != null) {
      Path rollback = folder.resolve(MigrationLoader.ROLLBACK_FOLDER).resolve(file.getFileName());
      Files.writeString(rollback, String.join("\n", rollbackParts));
    }
    out.println("Created " + project.display(file) + " replacing " + range.size()
        + " migration(s), " + range.get(0).getFileName() + " to " + target + ".");
    out.println("Databases that applied them record it without running it; new databases run "
        + "only this file.");
    out.println("Delete the old files once every environment has run 'migrax migrate'.");
    return OK;
  }

  private static String resolveFile(String wanted, List<String> names) {
    for (String name : names) {
      if (name.equals(wanted) || name.equals(wanted + ".sql")) {
        return name;
      }
    }
    for (String name : names) {
      if (name.startsWith(wanted)) {
        return name;
      }
    }
    throw new UsageException("Migration '" + wanted + "' not found.",
        "Run 'migrax status' to list migrations.");
  }
}
