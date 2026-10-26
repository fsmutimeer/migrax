package org.migrax.cli;

import org.migrax.runner.Migration;
import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import org.migrax.runner.SqlScript;
import org.migrax.util.Log;
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
    Map<String, List<String>> groups = groups(folder);
    groups.values().removeIf(list -> list.size() < 2);
    return groups;
  }

  /**
   * Like {@link #find}, with the project's Java migrations too: a SQL file and a Java migration
   * with the same number would run in an order nobody chose.
   */
  static Map<String, List<String>> find(Project project) throws Exception {
    Map<String, List<String>> groups = groups(project.migrations());
    for (Path source : project.javaMigrationSources()) {
      String name = source.getFileName().toString().replaceFirst("\\.java$", "");
      groups.computeIfAbsent(MigrationRunner.versionParts(name).toString(),
          k -> new ArrayList<>()).add(name);
    }
    groups.values().removeIf(list -> list.size() < 2);
    return groups;
  }

  /** Versioned files per migration number, excluding squashed ranges. */
  private static Map<String, List<String>> groups(Path folder) throws Exception {
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
    return groups;
  }

  /**
   * Duplicate numbers that still need 'migrax merge': groups whose files are all applied in the
   * configured database are settled history (they run in filename order) and are left out.
   * Without a reachable database every group is reported.
   */
  static Map<String, List<String>> unresolved(Project project, MigrationRunner runner)
      throws Exception {
    Map<String, List<String>> duplicates = find(project);
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
      throw new UsageException(ErrorCode.DUPLICATE_NUMBERS, "Migrations share numbers: " + duplicates.values() + ".",
          withJava(duplicates) ? advice(duplicates)
              : "This usually follows a git merge of two branches. Run 'migrax merge'.");
    }
  }

  /** What to do about the duplicates: 'migrax merge', or renaming when a Java migration is in. */
  static String advice(Map<String, List<String>> duplicates) {
    return withJava(duplicates)
        ? "Give each migration its own number: rename the one that is not applied yet, the SQL"
            + " file (with its rollback script) or the Java migration's class and file."
            + " 'migrax merge' renumbers SQL files only."
        : "Run 'migrax merge'.";
  }

  private static boolean withJava(Map<String, List<String>> duplicates) {
    return duplicates.values().stream().flatMap(List::stream)
        .anyMatch(name -> !name.endsWith(".sql"));
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
