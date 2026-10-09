package io.migrax.cli;

import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Finding and numbering migration files in the migration folder. */
final class MigrationFiles {
  private MigrationFiles() {}

  /** A migration file named on the command line, by file name, name without .sql or path. */
  static Path find(Project project, String name) throws Exception {
    Path migrations = project.migrations();
    for (Path candidate : List.of(migrations.resolve(name), migrations.resolve(name + ".sql"),
        project.root().resolve(name), Path.of(name))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new UsageException("Migration file not found: " + name,
        "Run 'migrax status' to list migrations in " + project.display(migrations) + ".");
  }

  /** The next migration number after the highest one in the folder. */
  static int nextNumber(Path folder) throws Exception {
    int highest = 0;
    for (Path file : MigrationLoader.sqlFiles(folder)) {
      List<java.math.BigInteger> version =
          MigrationRunner.versionParts(file.getFileName().toString());
      if (!version.isEmpty()) {
        highest = Math.max(highest, version.get(0).intValue());
      }
    }
    return highest + 1;
  }
}
