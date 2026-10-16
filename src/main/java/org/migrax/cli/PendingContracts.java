package org.migrax.cli;

import org.migrax.diff.SnapshotStore;
import org.migrax.runner.MigrationLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The contract migrations of {@code generate --zero-downtime}, waiting in
 * {@code .migrax/pending/} until the next release. Each is a migration file, its rollback
 * script in {@code rollback/} and the schema after it ({@code <name>.json}).
 */
final class PendingContracts {
  private PendingContracts() {}

  static Path folder(Project project) {
    return project.root().resolve(".migrax").resolve("pending");
  }

  /** The waiting contract migrations, oldest first. */
  static List<Path> list(Project project) throws Exception {
    Path folder = folder(project);
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(folder)) {
      return files.filter(Files::isRegularFile)
          .filter(f -> f.getFileName().toString().endsWith(".sql"))
          .sorted(org.migrax.runner.MigrationRunner.MIGRATION_PATH_COMPARATOR).toList();
    }
  }

  /** The JSON file with the schema after a waiting contract migration. */
  static Path schema(Path migration) {
    String name = migration.getFileName().toString();
    return migration.resolveSibling(name.substring(0, name.length() - ".sql".length())
        + ".json");
  }

  /**
   * Moves the waiting contract migrations into the migration folder, each with the next
   * number, together with their rollback scripts and history snapshots.
   *
   * @return the migration files written
   */
  static List<Path> release(Project project) throws Exception {
    List<Path> written = new ArrayList<>();
    Path migrations = project.migrations();
    Files.createDirectories(migrations.resolve(MigrationLoader.ROLLBACK_FOLDER));
    for (Path pending : list(project)) {
      String oldName = pending.getFileName().toString();
      String description = oldName.replaceFirst("^\\d+_", "");
      String newName = String.format(Locale.ROOT, "%04d_%s", project.nextNumber(), description);
      Path target = migrations.resolve(newName);
      Files.writeString(target, Files.readString(pending));
      Path rollback = pending.resolveSibling(MigrationLoader.ROLLBACK_FOLDER).resolve(oldName);
      if (Files.exists(rollback)) {
        Files.writeString(migrations.resolve(MigrationLoader.ROLLBACK_FOLDER).resolve(newName),
            Files.readString(rollback).replace("-- Rollback for " + oldName + ".",
                "-- Rollback for " + newName + "."));
        Files.delete(rollback);
      }
      Path schema = schema(pending);
      String base = newName.substring(0, newName.length() - ".sql".length());
      if (Files.exists(schema)) {
        Path history = SnapshotStore.historySnapshot(project.root(), base);
        Files.createDirectories(history.getParent());
        Files.move(schema, history, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      Files.delete(pending);
      written.add(target);
    }
    deleteIfEmpty(folder(project).resolve(MigrationLoader.ROLLBACK_FOLDER));
    deleteIfEmpty(folder(project));
    return written;
  }

  private static void deleteIfEmpty(Path folder) throws Exception {
    if (Files.isDirectory(folder)) {
      try (Stream<Path> children = Files.list(folder)) {
        if (children.findAny().isEmpty()) {
          Files.delete(folder);
        }
      }
    }
  }
}
