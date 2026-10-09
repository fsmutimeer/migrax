package io.migrax.cli;

import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;
import io.migrax.runner.SqlScript;
import io.migrax.util.Log;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Migrations that share a number, typically after merging two branches ({@code migrax merge}). */
final class DuplicateMigrations {
  private DuplicateMigrations() {}

  /** Migration numbers used by more than one versioned file, excluding squashed ranges. */
  static Map<String, List<String>> find(Path folder) throws Exception {
    List<Path> files = MigrationLoader.sqlFiles(folder);
    Set<String> replaced = new HashSet<>();
    for (Path file : files) {
      replaced.addAll(SqlScript.directiveValues(Files.readString(file), "replaces"));
    }
    Map<String, List<String>> groups = new TreeMap<>();
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (name.startsWith("R__") || Migration.CALLBACKS.contains(name) || replaced.contains(name)) {
        continue;
      }
      List<java.math.BigInteger> version = MigrationRunner.versionParts(name);
      if (!version.isEmpty()) {
        groups.computeIfAbsent(version.toString(), k -> new ArrayList<>()).add(name);
      }
    }
    groups.values().removeIf(list -> list.size() < 2);
    return groups;
  }

  /**
   * Duplicate numbers that still need 'migrax merge': groups whose files are all applied in the
   * configured database are settled history (they run in filename order) and are left out.
   * Without a reachable database every group is reported.
   */
  static Map<String, List<String>> unresolved(Project project, MigrationRunner runner)
      throws Exception {
    Map<String, List<String>> duplicates = find(project.migrations());
    if (duplicates.isEmpty() || !project.hasUrl()) {
      return duplicates;
    }
    Set<String> applied = appliedVersions(project, runner);
    duplicates.values().removeIf(applied::containsAll);
    return duplicates;
  }

  /** Fails with advice to run 'migrax merge' when {@link #unresolved} finds duplicates. */
  static void requireNone(Project project, MigrationRunner runner) throws Exception {
    Map<String, List<String>> duplicates = unresolved(project, runner);
    if (!duplicates.isEmpty()) {
      throw new UsageException("Migrations share numbers: " + duplicates.values() + ".",
          "This usually follows a git merge of two branches. Run 'migrax merge'.");
    }
  }

  private static Set<String> appliedVersions(Project project, MigrationRunner runner) {
    Set<String> applied = new HashSet<>();
    try (Connection connection = project.connect()) {
      runner.applied(connection).forEach(m -> applied.add(m.version()));
    } catch (Exception e) {
      Log.debug("Could not read the migration history: {}", Errors.describe(e));
    }
    return applied;
  }
}
