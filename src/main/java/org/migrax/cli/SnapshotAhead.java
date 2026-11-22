package org.migrax.cli;

import org.migrax.diff.SnapshotStore;
import org.migrax.runner.MigrationRunner;

import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds a snapshot that is ahead of the migration files: generate wrote a migration and moved
 * {@code snapshot.json} on, then the migration file was deleted. The next generate would find
 * nothing to do, so it stops instead, and {@code generate --rebuild-snapshot} moves the
 * snapshot back to the history snapshot of the newest generated migration before them. Each
 * generated migration leaves its model in {@code .migrax/history/<name>.json}; a history entry
 * newer than every migration file, whose file is gone, marks the deleted migration.
 */
final class SnapshotAhead {
  private static final Pattern NUMBER = Pattern.compile("^[vV]?(\\d+)");

  private SnapshotAhead() {}

  /** Names of the deleted migrations the snapshot still includes, oldest first; often empty. */
  static List<String> deleted(Project project) throws Exception {
    BigInteger highest = project.nextNumber().subtract(BigInteger.ONE);
    List<String> deleted = new ArrayList<>();
    for (String name : historyNames(project)) {
      BigInteger number = number(name);
      if (number != null && number.compareTo(highest) > 0
          && project.migrationFile(name + ".sql") == null) {
        deleted.add(name);
      }
    }
    return deleted;
  }

  /** Stops generate when migrations were deleted after their snapshot was saved. */
  static void requireNone(Project project) throws Exception {
    List<String> deleted = deleted(project);
    if (deleted.isEmpty()) {
      return;
    }
    List<String> files = deleted.stream().map(name -> name + ".sql").toList();
    throw new UsageException(ErrorCode.SNAPSHOT_AHEAD, project.display(project.snapshot())
        + " is ahead of the migration files: it includes " + String.join(", ", files)
        + ", which " + (files.size() == 1 ? "is" : "are") + " no longer in the migration folder.",
        "Restore the file, or, if you deleted it on purpose, run 'migrax generate "
            + "--rebuild-snapshot' and then 'migrax generate' again. If you renumbered it, "
            + "rename its file in " + project.display(project.state().resolve("history"))
            + " too.");
  }

  /**
   * Moves the snapshot back to the history snapshot of the newest generated migration before the
   * deleted ones, and removes the history of the deleted ones. Without any migration left, the
   * snapshot is removed, so the next generate starts from the database as on a first run.
   */
  static int rebuild(CommandContext context) throws Exception {
    Project project = context.project();
    PrintStream out = context.out();
    List<String> deleted = deleted(project);
    context.result("rebuilt", !deleted.isEmpty());
    if (deleted.isEmpty()) {
      out.println(project.display(project.snapshot())
          + " matches the migration files; nothing to rebuild.");
      return ExitCode.OK;
    }
    if (project.migrationFiles().isEmpty() && !context.args().flag("--yes", "-y")) {
      // A wrong --locations or migration folder makes every generated migration look deleted.
      throw new UsageException("No migration file found in " + project.displayMigrations()
          + ", so this would remove " + project.display(project.snapshot()) + " and the history "
          + "of " + String.join(", ", deleted.stream().map(name -> name + ".sql").toList()) + ".",
          "Check --locations and the migration folder. If you deleted every migration on "
              + "purpose, rerun with --yes.");
    }
    Path snapshot = project.snapshot();
    String newest = historyNames(project).stream()
        .filter(name -> !deleted.contains(name))
        .filter(name -> number(name) != null)
        .max(Comparator.comparing(SnapshotAhead::number)
            .thenComparing(MigrationRunner::compareMigrationFilenames))
        .orElse(null);
    if (newest == null) {
      Files.deleteIfExists(snapshot);
      out.println("Removed " + project.display(snapshot) + ": no generated migration is left. "
          + "The next 'migrax generate' starts from the database.");
    } else {
      Path history = project.historySnapshot(newest);
      String dialect = Files.exists(snapshot) ? SnapshotStore.dialect(snapshot) : null;
      String naming = Files.exists(snapshot) ? SnapshotStore.naming(snapshot) : null;
      String server = Files.exists(snapshot) ? SnapshotStore.server(snapshot) : null;
      SnapshotStore.save(snapshot, SnapshotStore.load(history),
          dialect != null ? dialect : SnapshotStore.dialect(history), naming, server);
      out.println("Rebuilt " + project.display(snapshot) + " from the history snapshot of "
          + newest + ".sql.");
    }
    for (String name : deleted) {
      Files.deleteIfExists(project.historySnapshot(name));
      context.resultList("forgotten").add(name + ".sql");
      out.println("Removed " + project.display(project.historySnapshot(name)) + ".");
    }
    out.println("Next: run 'migrax generate' to write the changes again.");
    return ExitCode.OK;
  }

  /** Migration names (without .sql) that have a history snapshot. */
  private static List<String> historyNames(Project project) throws Exception {
    Path folder = project.state().resolve("history");
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.map(file -> String.valueOf(file.getFileName()))
          .filter(name -> name.endsWith(".json"))
          .map(name -> name.substring(0, name.length() - 5))
          .sorted(MigrationRunner::compareMigrationFilenames)
          .toList();
    }
  }

  private static BigInteger number(String name) {
    Matcher matcher = NUMBER.matcher(name);
    return matcher.find() ? new BigInteger(matcher.group(1)) : null;
  }
}
