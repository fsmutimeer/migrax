package org.migrax.verify;

import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.runner.Migration;
import org.migrax.runner.MigrationRunner;
import org.migrax.util.Messages;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests the rollback scripts of a migrated database: each one is rolled back, applied again and
 * compared with the schema before it, so a broken script is named. Used by {@code migrax verify}
 * and the Java API.
 *
 * @since 0.10.0
 */
public final class RollbackVerifier {

  /** Finds the rollback script of an applied SQL migration. */
  @FunctionalInterface
  public interface Scripts {
    /** The rollback script's SQL, or null when the migration has none. */
    String sql(String version) throws Exception;
  }

  private RollbackVerifier() {}

  /**
   * Rolls back the newest migrations that have rollback scripts (all of them when every one
   * has a script) and applies them again.
   *
   * @return a short result: starts with {@code ok}, {@code skipped} or {@code failed:}
   */
  public static String check(Connection connection, MigrationRunner runner,
                             List<Migration> migrations, MigrationRunner.Options options,
                             String dialect, Scripts rollbackScripts) throws Exception {
    Map<String, Migration> java = new HashMap<>();
    migrations.stream().filter(Migration::isJava).forEach(m -> java.put(m.version(), m));
    List<MigrationRunner.AppliedMigration> applied = runner.applied(connection).stream()
        .filter(m -> !m.version().startsWith("R__")).toList();
    List<String> versions = new ArrayList<>();
    List<Migration> scripts = new ArrayList<>();
    String firstWithout = null;
    for (int i = applied.size() - 1; i >= 0; i--) {
      String version = applied.get(i).version();
      String sql = java.containsKey(version) ? null : rollbackScripts.sql(version);
      if (java.containsKey(version)) {
        scripts.add(java.get(version));
      } else if (sql != null) {
        scripts.add(Migration.ofSql(version, sql));
      } else {
        firstWithout = version;
        break;
      }
      versions.add(version);
    }
    if (scripts.isEmpty()) {
      return firstWithout == null ? "skipped (no migrations)"
          : "skipped (" + firstWithout + " has no rollback script)";
    }
    try {
      SchemaModel before = DatabaseSchemaReader.read(connection);
      for (int i = 0; i < scripts.size(); i++) {
        String failure = rollBackAndReapply(connection, runner, migrations, options,
            versions.get(i), scripts.get(i), dialect);
        if (failure != null) {
          return "failed: " + failure;
        }
        runner.rollback(connection, versions.get(i), scripts.get(i), options);
      }
      if (firstWithout == null) {
        SchemaModel left = DatabaseSchemaReader.read(connection);
        if (!left.tables().isEmpty()) {
          return "failed: tables left after rolling everything back: "
              + left.tables().stream().map(SchemaModel.Table::name).toList();
        }
      }
      runner.migrateAll(connection, migrations, options);
      List<SchemaComparator.Difference> changed = SchemaComparator.compare(before,
          DatabaseSchemaReader.read(connection), dialect);
      if (!changed.isEmpty()) {
        return "failed: the schema differs after rolling back and re-applying: "
            + changed.stream().map(SchemaComparator.Difference::toString).toList();
      }
      return "ok (" + scripts.size() + " rolled back and re-applied"
          + (firstWithout == null ? "" : "; older ones from " + firstWithout
              + " down have no rollback script") + ")";
    } catch (Exception e) {
      return "failed: " + Messages.describe(e);
    }
  }

  /**
   * Tests one rollback script on its own: rolls the migration back, applies it again and
   * compares the schema with the one before. Checking only the end result misses a script that
   * leaves something behind when an older script removes it anyway (an index on a table that is
   * dropped later). Returns what went wrong, naming the script, or null.
   */
  private static String rollBackAndReapply(Connection connection, MigrationRunner runner,
                                           List<Migration> migrations,
                                           MigrationRunner.Options options, String version,
                                           Migration script, String dialect) throws Exception {
    SchemaModel before = DatabaseSchemaReader.read(connection);
    try {
      runner.rollback(connection, version, script, options);
    } catch (Exception e) {
      return "the rollback script of " + version + " fails: "
          + Messages.describe(databaseError(e));
    }
    try {
      runner.migrateAll(connection, migrations, options.withTarget(version));
    } catch (Exception e) {
      return version + " fails when applied again after its rollback script ran: "
          + Messages.describe(databaseError(e)) + " (the rollback script may leave something "
          + "behind)";
    }
    List<SchemaComparator.Difference> changed = SchemaComparator.compare(before,
        DatabaseSchemaReader.read(connection), dialect);
    if (!changed.isEmpty()) {
      return "rolling back " + version + " and applying it again changes the schema: "
          + changed.stream().map(SchemaComparator.Difference::toString).toList();
    }
    return null;
  }

  /** The database's own error, without the runner's advice for a real database. */
  private static Throwable databaseError(Exception e) {
    return e instanceof java.sql.SQLException && e.getCause() != null ? e.getCause() : e;
  }
}
