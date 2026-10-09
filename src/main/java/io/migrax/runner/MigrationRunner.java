package io.migrax.runner;

import io.migrax.util.Log;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Applies migrations and keeps the history in {@code migrax_history} and
 * {@code migrax_failures}.
 *
 * <p>Applied migrations are verified by checksum, failed or interrupted ones block further runs
 * until they are repaired, and concurrent runs are serialized with a database lock.
 *
 * @since 0.1.0
 */
public final class MigrationRunner {
  private static final Pattern LEADING_NUMBER = Pattern.compile("^(?:[vV])?(\\d+)");
  private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.$-]*");
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  public static final Comparator<Path> MIGRATION_PATH_COMPARATOR = (p1, p2) ->
      compareMigrationFilenames(p1.getFileName().toString(), p2.getFileName().toString());

  /** Orders migrations by their leading version number, then by name. */
  public static final Comparator<Migration> ORDER =
      (a, b) -> compareMigrationFilenames(a.version(), b.version());

  /** Run options. */
  public record Options(boolean resume, Map<String, String> placeholders) {
    public Options {
      placeholders = placeholders == null ? Map.of() : Map.copyOf(placeholders);
    }

    public static Options defaults() {
      return new Options(false, Map.of());
    }
  }

  /** A row of {@code migrax_history}. */
  public record AppliedMigration(String version, String checksum, String appliedAt) {}

  /**
   * Orders by version: the leading number of {@code 0002_add_email.sql}, or the dotted version
   * of {@code V1.10__name.sql} / {@code V1_10__name.sql}; then by name.
   */
  public static int compareMigrationFilenames(String n1, String n2) {
    List<java.math.BigInteger> v1 = versionParts(n1);
    List<java.math.BigInteger> v2 = versionParts(n2);
    if (!v1.isEmpty() && !v2.isEmpty()) {
      for (int i = 0; i < Math.max(v1.size(), v2.size()); i++) {
        java.math.BigInteger a = i < v1.size() ? v1.get(i) : java.math.BigInteger.ZERO;
        java.math.BigInteger b = i < v2.size() ? v2.get(i) : java.math.BigInteger.ZERO;
        int cmp = a.compareTo(b);
        if (cmp != 0) {
          return cmp;
        }
      }
    }
    return n1.compareTo(n2);
  }

  /** Version numbers of a migration name, empty when it has none. */
  public static List<java.math.BigInteger> versionParts(String name) {
    List<java.math.BigInteger> parts = new ArrayList<>();
    int separator = name.indexOf("__");
    if ((name.startsWith("V") || name.startsWith("v")) && separator > 1) {
      String version = name.substring(1, separator);
      if (version.matches("[0-9]+([._][0-9]+)*")) {
        for (String part : version.split("[._]")) {
          parts.add(new java.math.BigInteger(part));
        }
        return parts;
      }
    }
    var matcher = LEADING_NUMBER.matcher(name);
    if (matcher.find()) {
      parts.add(new java.math.BigInteger(matcher.group(1)));
    }
    return parts;
  }

  // ------------------------------------------------------------------ migrate

  public int migrate(Connection connection, List<Path> files) throws Exception {
    return migrate(connection, files, false);
  }

  public int migrate(Connection connection, List<Path> files, boolean resume) throws Exception {
    return migrateAll(connection, Migration.load(files), new Options(resume, Map.of()));
  }

  /**
   * Applies pending versioned migrations in order, then new or changed repeatable migrations,
   * running callbacks around them.
   *
   * @return the number of migrations applied
   */
  public int migrateAll(Connection connection, List<Migration> migrations, Options options)
      throws Exception {
    validateVersions(migrations);
    return withTransactionAndLock(connection, () -> {
      ensureHistory(connection);
      ensureFailureTable(connection);
      boolean backslashEscapes = backslashEscapes(connection);

      List<Migration> versioned = migrations.stream()
          .filter(m -> m.kind() == Migration.Kind.VERSIONED).sorted(ORDER).toList();
      List<Migration> repeatable = migrations.stream()
          .filter(m -> m.kind() == Migration.Kind.REPEATABLE)
          .sorted(Comparator.comparing(Migration::version)).toList();
      Map<String, Migration> callbacks = new HashMap<>();
      migrations.stream().filter(m -> m.kind() == Migration.Kind.CALLBACK)
          .forEach(m -> callbacks.put(m.version(), m));

      // Parse every script before running anything, so a broken file changes nothing.
      Map<String, List<String>> statements = new HashMap<>();
      for (Migration migration : migrations) {
        if (!migration.isJava()) {
          statements.put(migration.version(), SqlScript.split(
              SqlScript.substitute(migration.sql(), options.placeholders()), backslashEscapes));
        }
      }

      Map<String, AppliedMigration> history = readHistory(connection);
      verifyAppliedExist(connection, history, migrations);
      verifyAppliedChecksums(history, versioned);
      verifyFailedAttempts(connection, migrations, options.resume());

      int applied = 0;
      runCallback(connection, callbacks.get("beforeMigrate.sql"), statements);
      // Squashed migrations: a database that applied none of the replaced migrations runs the
      // squashed file; one that applied some keeps applying the originals. Either way all of
      // them end up recorded.
      Map<String, Migration> squashOf = new HashMap<>();
      Map<String, Migration> byVersion = new HashMap<>();
      for (Migration migration : versioned) {
        byVersion.put(migration.version(), migration);
        migration.replaces().forEach(replaced -> squashOf.put(replaced, migration));
      }
      Set<String> done = new HashSet<>(history.keySet());
      for (Migration migration : versioned) {
        if (done.contains(migration.version())) {
          Log.debug("Migration '{}' is already applied; skipping.", migration.version());
          continue;
        }
        Migration squash = migration.replaces().isEmpty() ? squashOf.get(migration.version())
            : migration;
        if (squash == null) {
          applyMigration(connection, migration, statements, callbacks, options.resume(), false);
          done.add(migration.version());
          applied++;
          continue;
        }
        List<String> replaces = squash.replaces();
        boolean noneApplied = replaces.stream().noneMatch(done::contains);
        if (done.contains(squash.version())) {
          // The squash already ran here; the original file only needs recording.
          recordApplied(connection, migration.version(), migration.checksum(), false);
          connection.commit();
          done.add(migration.version());
        } else if (noneApplied) {
          applyMigration(connection, squash, statements, callbacks, options.resume(), false);
          done.add(squash.version());
          applied++;
          for (String replaced : replaces) {
            Migration original = byVersion.get(replaced);
            recordApplied(connection, replaced,
                original == null ? squash.checksum() : original.checksum(), false);
            done.add(replaced);
          }
          connection.commit();
        } else if (migration != squash) {
          applyMigration(connection, migration, statements, callbacks, options.resume(), false);
          done.add(migration.version());
          applied++;
        }
      }
      for (Migration migration : versioned) {
        List<String> replaces = migration.replaces();
        if (!replaces.isEmpty() && !done.contains(migration.version())) {
          if (!done.containsAll(replaces)) {
            List<String> missing = replaces.stream().filter(r -> !done.contains(r)).toList();
            throw new IllegalStateException("Squashed migration '" + migration.version()
                + "' replaces migrations that are only partly applied here and the files "
                + missing + " are gone. Restore them until every environment has applied "
                + "the squashed range.");
          }
          recordApplied(connection, migration.version(), migration.checksum(), false);
          connection.commit();
          done.add(migration.version());
          Log.info("Recorded '{}' as applied: it replaces migrations already applied here.",
              migration.version());
        }
      }
      for (Migration migration : repeatable) {
        AppliedMigration previous = history.get(migration.version());
        if (previous != null && previous.checksum().equals(migration.checksum())) {
          continue;
        }
        applyMigration(connection, migration, statements, callbacks, options.resume(),
            previous != null);
        applied++;
      }
      runCallback(connection, callbacks.get("afterMigrate.sql"), statements);
      return applied;
    });
  }

  private void applyMigration(Connection connection, Migration migration,
                              Map<String, List<String>> statements,
                              Map<String, Migration> callbacks, boolean resume,
                              boolean replaceHistory) throws Exception {
    String version = migration.version();
    String checksum = migration.checksum();
    FailureRecord existingFailure = findFailure(connection, version);
    if (existingFailure != null) {
      checkResumable(migration, existingFailure, resume);
      setFailureState(connection, version, checksum, "RUNNING",
          "Retry requested explicitly; migration is marked resume-safe.");
    } else {
      setFailureState(connection, version, checksum, "RUNNING", null);
    }
    connection.commit();

    Log.info("Applying migration '{}'...", version);
    try {
      execute(connection, callbacks.get("beforeEachMigrate.sql"), statements);
      if (migration.isJava()) {
        migration.java().migrate(connection);
      } else if (SqlScript.hasDirective(migration.sql(), "no-transaction")) {
        // CREATE INDEX CONCURRENTLY and similar statements cannot run in a transaction.
        connection.commit();
        connection.setAutoCommit(true);
        try {
          executeStatements(connection, statements.get(version));
        } finally {
          connection.setAutoCommit(false);
        }
      } else {
        executeStatements(connection, statements.get(version));
      }
      execute(connection, callbacks.get("afterEachMigrate.sql"), statements);
      recordApplied(connection, version, checksum, replaceHistory);
      deleteFailure(connection, version);
      connection.commit();
      Log.info("Successfully applied migration '{}'.", version);
    } catch (Exception e) {
      Log.error("Failed executing migration '{}': {}", version, e.getMessage());
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      try {
        setFailureState(connection, version, checksum, "FAILED",
            failureMessage(e));
        connection.commit();
      } catch (Exception recordFailure) {
        e.addSuppressed(recordFailure);
      }
      throw new SQLException(
          "Migration '" + version + "' failed and was not recorded as applied. Some database "
              + "engines implicitly commit DDL; inspect the schema. Default retries are blocked. "
              + "Use the explicit repair goal or guarded resume only after verifying the database.",
          e);
    }
  }

  private static void checkResumable(Migration migration, FailureRecord failure, boolean resume) {
    String version = migration.version();
    if (!failure.checksum().equals(migration.checksum())) {
      throw new IllegalStateException(
          "Failed migration '" + version + "' was edited after failure. Inspect the database "
              + "and use the explicit repair goal before changing or retrying this file.");
    }
    if (!resume) {
      throw new IllegalStateException(
          "Migration '" + version + "' has an incomplete/failed attempt. Inspect the database "
              + "and use repair, or request guarded resume if the migration is marked resume-safe.");
    }
    if (migration.isJava() || !SqlScript.hasDirective(migration.sql(), "resume-safe")) {
      throw new IllegalStateException(
          "Migration '" + version + "' is not marked with '-- migrax:resume-safe'. "
              + "Do not automatically replay arbitrary SQL after a partial failure.");
    }
  }

  private void runCallback(Connection connection, Migration callback,
                           Map<String, List<String>> statements) throws Exception {
    if (callback != null) {
      Log.debug("Running callback '{}'.", callback.version());
      execute(connection, callback, statements);
      connection.commit();
    }
  }

  private static void execute(Connection connection, Migration migration,
                              Map<String, List<String>> statements) throws Exception {
    if (migration != null) {
      executeStatements(connection, statements.get(migration.version()));
    }
  }

  private static void executeStatements(Connection connection, List<String> commands)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      for (String command : commands) {
        statement.execute(command);
      }
    }
  }

  // ------------------------------------------------------------------ rollback

  /**
   * Reverts an applied migration with its rollback script (or {@link
   * io.migrax.api.JavaMigration#rollback}) and removes it from the history.
   *
   * @param version the applied migration to revert
   * @param rollback the rollback script, or the Java migration itself
   */
  public void rollback(Connection connection, String version, Migration rollback,
                       Options options) throws Exception {
    withTransactionAndLock(connection, () -> {
      ensureHistory(connection);
      ensureFailureTable(connection);
      if (!readHistory(connection).containsKey(version)) {
        throw new IllegalStateException("Migration '" + version + "' is not applied here.");
      }
      Log.info("Rolling back migration '{}'...", version);
      try {
        if (rollback.isJava()) {
          rollback.java().rollback(connection);
        } else {
          List<String> commands = SqlScript.split(
              SqlScript.substitute(rollback.sql(), options.placeholders()),
              backslashEscapes(connection));
          if (SqlScript.hasDirective(rollback.sql(), "no-transaction")) {
            connection.commit();
            connection.setAutoCommit(true);
            try {
              executeStatements(connection, commands);
            } finally {
              connection.setAutoCommit(false);
            }
          } else {
            executeStatements(connection, commands);
          }
        }
        try (PreparedStatement delete =
                 connection.prepareStatement("DELETE FROM migrax_history WHERE version=?")) {
          delete.setString(1, version);
          delete.executeUpdate();
        }
        deleteFailure(connection, version);
        connection.commit();
        Log.info("Rolled back migration '{}'.", version);
      } catch (Exception e) {
        throw new SQLException("Rolling back '" + version + "' failed; it stays recorded as "
            + "applied. Some databases commit DDL immediately, so inspect the schema.", e);
      }
      return null;
    });
  }

  // ------------------------------------------------------------------ status

  /** Migration state reported by {@link #status(Connection, List)}. */
  public enum State {
    /** Applied and the file checksum still matches. */
    APPLIED,
    /** Not applied yet. */
    PENDING,
    /** A previous attempt failed or was interrupted; repair or guarded resume is required. */
    FAILED,
    /** Applied, but the file was edited afterwards. */
    CHANGED,
    /** A repeatable migration whose content changed; it runs again on the next migrate. */
    OUTDATED,
    /** Recorded in the database, but the file is missing from the migration folder. */
    MISSING
  }

  /** One row of migration status. */
  public record MigrationStatus(String version, State state, String detail) {}

  public List<MigrationStatus> status(Connection connection, List<Path> files) throws Exception {
    return statusOf(connection, Migration.load(files));
  }

  /**
   * Reports applied, pending, failed, changed and missing migrations without changing the
   * database. History tables that do not exist yet mean every migration is pending.
   */
  public List<MigrationStatus> statusOf(Connection connection, List<Migration> migrations)
      throws Exception {
    Map<String, AppliedMigration> history = historyTableExists(connection, "migrax_history")
        ? readHistory(connection) : Map.of();
    Map<String, String[]> failures = new HashMap<>();
    if (historyTableExists(connection, "migrax_failures")) {
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(
               "SELECT version, failure_state, failure_message FROM migrax_failures")) {
        while (result.next()) {
          failures.put(result.getString(1),
              new String[]{result.getString(2), result.getString(3)});
        }
      }
    }

    List<MigrationStatus> rows = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    List<Migration> ordered = new ArrayList<>(migrations.stream()
        .filter(m -> m.kind() == Migration.Kind.VERSIONED).sorted(ORDER).toList());
    migrations.stream().filter(m -> m.kind() == Migration.Kind.REPEATABLE)
        .sorted(Comparator.comparing(Migration::version)).forEach(ordered::add);
    for (Migration migration : ordered) {
      String version = migration.version();
      seen.add(version);
      seen.addAll(migration.replaces());
      AppliedMigration applied = history.get(version);
      String[] failure = failures.get(version);
      if (failure != null) {
        rows.add(new MigrationStatus(version, State.FAILED,
            failure[0].toLowerCase(Locale.ROOT) + (failure[1] == null ? "" : ": " + failure[1])));
      } else if (applied == null) {
        List<String> replaces = migration.replaces();
        boolean replaced = !replaces.isEmpty() && history.keySet().containsAll(replaces);
        rows.add(new MigrationStatus(version, State.PENDING,
            replaced ? "replaces applied migrations; will be recorded without running" : ""));
      } else if (applied.checksum().equals(migration.checksum())) {
        rows.add(new MigrationStatus(version, State.APPLIED, "applied " + applied.appliedAt()));
      } else if (migration.kind() == Migration.Kind.REPEATABLE) {
        rows.add(new MigrationStatus(version, State.OUTDATED,
            "changed since it was applied " + applied.appliedAt() + "; runs again on migrate"));
      } else {
        rows.add(new MigrationStatus(version, State.CHANGED,
            "file changed after it was applied " + applied.appliedAt()));
      }
    }
    Set<String> recorded = new TreeSet<>(MigrationRunner::compareMigrationFilenames);
    recorded.addAll(history.keySet());
    recorded.addAll(failures.keySet());
    for (String version : recorded) {
      if (!seen.contains(version) && !version.startsWith("R__")) {
        rows.add(new MigrationStatus(version, State.MISSING,
            "recorded in the database but the file is missing"));
      }
    }
    return List.copyOf(rows);
  }

  /** Applied migrations in version order; empty when the history table does not exist. */
  public List<AppliedMigration> applied(Connection connection) throws SQLException {
    if (!historyTableExists(connection, "migrax_history")) {
      return List.of();
    }
    List<AppliedMigration> rows = new ArrayList<>(readHistory(connection).values());
    rows.sort((a, b) -> compareMigrationFilenames(a.version(), b.version()));
    return rows;
  }

  /**
   * Records migrations as applied without running them, for adopting an existing database
   * (for example one managed by another migration tool).
   *
   * @return the number of migrations newly recorded
   */
  public int markApplied(Connection connection, List<Migration> migrations) throws Exception {
    return withTransactionAndLock(connection, () -> {
      ensureHistory(connection);
      ensureFailureTable(connection);
      Map<String, AppliedMigration> history = readHistory(connection);
      int recorded = 0;
      for (Migration migration : migrations) {
        if (!history.containsKey(migration.version())) {
          recordApplied(connection, migration.version(), migration.checksum(), false);
          recorded++;
        }
      }
      connection.commit();
      return recorded;
    });
  }

  // ------------------------------------------------------------------ repair

  public void repair(Connection connection, Path migrationFile, String action, boolean confirm)
      throws Exception {
    repair(connection, Migration.load(migrationFile), action, confirm);
  }

  public void repair(Connection connection, Migration migration, String action, boolean confirm)
      throws Exception {
    if (!confirm) {
      throw new IllegalArgumentException(
          "Repair changes migration history. Pass explicit confirmation after inspecting the database.");
    }
    String normalizedAction = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
    if (!normalizedAction.equals("applied") && !normalizedAction.equals("retry")) {
      throw new IllegalArgumentException("Repair action must be 'applied' or 'retry'.");
    }
    String version = migration.version();
    withTransactionAndLock(connection, () -> {
      ensureHistory(connection);
      ensureFailureTable(connection);
      FailureRecord failed = findFailure(connection, version);
      if (failed == null) {
        throw new IllegalStateException(
            "No incomplete migration record exists for " + version + "; nothing to repair.");
      }
      if (!failed.checksum().equals(migration.checksum())) {
        throw new IllegalStateException(
            "The migration checksum differs from the failed attempt. Restore the original file "
                + "before repairing migration history.");
      }
      if (normalizedAction.equals("applied")) {
        recordApplied(connection, version, migration.checksum(), true);
      }
      deleteFailure(connection, version);
      connection.commit();
      return null;
    });
  }

  /**
   * Removes a migration from the history, for a file that was deleted on purpose. Whatever the
   * migration changed stays in the database; only the record goes.
   *
   * @return {@code true} when the history had a record (applied or failed) for it
   */
  public boolean forget(Connection connection, String version, boolean confirm) throws Exception {
    if (!confirm) {
      throw new IllegalArgumentException(
          "Forgetting a migration changes migration history. Pass explicit confirmation.");
    }
    return withTransactionAndLock(connection, () -> {
      ensureHistory(connection);
      ensureFailureTable(connection);
      int removed;
      try (PreparedStatement delete =
               connection.prepareStatement("DELETE FROM migrax_history WHERE version=?")) {
        delete.setString(1, version);
        removed = delete.executeUpdate();
      }
      try (PreparedStatement delete =
               connection.prepareStatement("DELETE FROM migrax_failures WHERE version=?")) {
        delete.setString(1, version);
        removed += delete.executeUpdate();
      }
      connection.commit();
      return removed > 0;
    });
  }

  // ------------------------------------------------------------------ verification

  private static void validateVersions(List<Migration> migrations) {
    Set<String> versions = new HashSet<>();
    for (Migration migration : migrations) {
      String version = migration.version();
      if (!VALID_NAME.matcher(version).matches()) {
        throw new IllegalArgumentException("Invalid migration filename: " + version);
      }
      if (!versions.add(version.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("Duplicate migration filename: " + version);
      }
    }
  }

  private static void verifyAppliedExist(Connection connection,
                                         Map<String, AppliedMigration> history,
                                         List<Migration> migrations) throws SQLException {
    Set<String> available = new HashSet<>();
    for (Migration migration : migrations) {
      available.add(migration.version());
      available.addAll(migration.replaces());
    }
    Set<String> recorded = new LinkedHashSet<>(history.keySet());
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version FROM migrax_failures")) {
      while (result.next()) {
        recorded.add(result.getString(1));
      }
    }
    List<String> missing = new ArrayList<>();
    for (String version : recorded) {
      // A deleted repeatable migration simply stops being re-applied.
      if (!available.contains(version) && !version.startsWith("R__")) {
        missing.add(version);
      }
    }
    if (!missing.isEmpty()) {
      missing.sort(MigrationRunner::compareMigrationFilenames);
      throw new IllegalStateException(
          "Applied migration file(s) are missing from the configured migration location: "
              + String.join(", ", missing)
              + ". Restore the files from version control; applied migrations must not be deleted."
              + " If you deleted them on purpose, remove them from the history with: migrax repair "
              + String.join(" ", missing) + " --action forget --yes");
    }
  }

  private static void verifyAppliedChecksums(Map<String, AppliedMigration> history,
                                             List<Migration> versioned) {
    for (Migration migration : versioned) {
      AppliedMigration applied = history.get(migration.version());
      if (applied != null && !applied.checksum().equals(migration.checksum())) {
        throw new IllegalStateException(
            "Checksum changed for applied migration " + migration.version());
      }
    }
  }

  private static void verifyFailedAttempts(Connection connection, List<Migration> migrations,
                                           boolean resume) throws SQLException {
    Map<String, Migration> available = new HashMap<>();
    migrations.forEach(m -> available.put(m.version(), m));
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT version, checksum, failure_state FROM migrax_failures")) {
      while (result.next()) {
        Migration migration = available.get(result.getString(1));
        if (migration != null) {
          checkResumable(migration, new FailureRecord(result.getString(2), result.getString(3)),
              resume);
        }
      }
    }
  }

  // ------------------------------------------------------------------ history tables

  private static Map<String, AppliedMigration> readHistory(Connection connection)
      throws SQLException {
    Map<String, AppliedMigration> history = new LinkedHashMap<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT version, checksum, applied_at FROM migrax_history")) {
      while (result.next()) {
        java.sql.Timestamp appliedAt = result.getTimestamp(3);
        history.put(result.getString(1), new AppliedMigration(result.getString(1),
            result.getString(2),
            appliedAt == null ? "" : appliedAt.toLocalDateTime().format(TIMESTAMP)));
      }
    }
    return history;
  }

  private static void recordApplied(Connection connection, String version, String checksum,
                                    boolean replace) throws SQLException {
    if (replace) {
      try (PreparedStatement delete =
               connection.prepareStatement("DELETE FROM migrax_history WHERE version=?")) {
        delete.setString(1, version);
        delete.executeUpdate();
      }
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "INSERT INTO migrax_history(version,checksum,applied_at) "
            + "VALUES(?,?," + currentTimestamp(connection) + ")")) {
      insert.setString(1, version);
      insert.setString(2, checksum);
      insert.executeUpdate();
    }
  }

  private void ensureHistory(Connection connection) throws SQLException {
    if (!historyTableExists(connection, "migrax_history")) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE migrax_history (version VARCHAR(255) PRIMARY KEY, "
                + "checksum VARCHAR(64) NOT NULL, "
                + "applied_at " + timestampType(connection) + " NOT NULL)");
      }
    }
  }

  private void ensureFailureTable(Connection connection) throws SQLException {
    if (!historyTableExists(connection, "migrax_failures")) {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE migrax_failures (version VARCHAR(255) PRIMARY KEY, "
                + "checksum VARCHAR(64) NOT NULL, failure_state VARCHAR(16) NOT NULL, "
                + "attempt_count INTEGER NOT NULL, failure_message VARCHAR(2000), "
                + "updated_at " + timestampType(connection) + " NOT NULL)");
      }
    }
  }

  /**
   * Checks for a Migrax table in the connection's current catalog and schema. Looking only in
   * the current schema matters on PostgreSQL and SQL Server, where another schema may hold a
   * table with the same name; the unqualified CREATE TABLE also targets the current schema.
   */
  static boolean historyTableExists(Connection connection, String name) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    String catalog = connection.getCatalog();
    String schema = currentSchema(connection);
    return tableExists(metadata, catalog, schema, name)
        || tableExists(metadata, catalog, schema, name.toUpperCase(Locale.ROOT));
  }

  private static String currentSchema(Connection connection) {
    try {
      String schema = connection.getSchema();
      return schema == null || schema.isBlank() ? null : schema;
    } catch (SQLException | AbstractMethodError | UnsupportedOperationException e) {
      return null;
    }
  }

  private static boolean tableExists(
      DatabaseMetaData metadata, String catalog, String schema, String name) throws SQLException {
    try (ResultSet tables = metadata.getTables(catalog, schema, name, new String[]{"TABLE"})) {
      return tables.next();
    }
  }

  private static boolean backslashEscapes(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (!product.contains("mysql") && !product.contains("mariadb")) {
      return false;
    }
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
      return !result.next() || !result.getString(1).toUpperCase(Locale.ROOT)
          .contains("NO_BACKSLASH_ESCAPES");
    }
  }

  private static String timestampType(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    return product.contains("microsoft sql server") ? "DATETIME2" : "TIMESTAMP";
  }

  private static String currentTimestamp(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    if (product.contains("microsoft sql server")) {
      return "SYSUTCDATETIME()";
    }
    if (product.contains("oracle")) {
      return "SYSTIMESTAMP";
    }
    return "CURRENT_TIMESTAMP";
  }

  private static FailureRecord findFailure(Connection connection, String version)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT checksum, failure_state FROM migrax_failures WHERE version=?")) {
      query.setString(1, version);
      try (ResultSet result = query.executeQuery()) {
        return result.next() ? new FailureRecord(result.getString(1), result.getString(2)) : null;
      }
    }
  }

  /** The database's own error, which 'status' shows to help choose a repair action. */
  static String failureMessage(Exception e) {
    String message = e.getClass().getSimpleName()
        + (e.getMessage() == null ? "" : ": " + e.getMessage().strip());
    return message.length() > 1000 ? message.substring(0, 997) + "..." : message;
  }

  private static void setFailureState(
      Connection connection, String version, String checksum, String state, String message)
      throws SQLException {
    try (PreparedStatement update = connection.prepareStatement(
        "UPDATE migrax_failures SET checksum=?, failure_state=?, "
            + "attempt_count=attempt_count+?, failure_message=?, "
            + "updated_at=" + currentTimestamp(connection) + " WHERE version=?")) {
      update.setString(1, checksum);
      update.setString(2, state);
      update.setInt(3, "RUNNING".equals(state) ? 1 : 0);
      update.setString(4, message);
      update.setString(5, version);
      if (update.executeUpdate() == 0) {
        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO migrax_failures(version,checksum,failure_state,attempt_count,"
                + "failure_message,updated_at) VALUES(?,?,?,?,?,"
                + currentTimestamp(connection) + ")")) {
          insert.setString(1, version);
          insert.setString(2, checksum);
          insert.setString(3, state);
          insert.setInt(4, 1);
          insert.setString(5, message);
          insert.executeUpdate();
        }
      }
    }
  }

  private static void deleteFailure(Connection connection, String version) throws SQLException {
    try (PreparedStatement delete =
             connection.prepareStatement("DELETE FROM migrax_failures WHERE version=?")) {
      delete.setString(1, version);
      delete.executeUpdate();
    }
  }

  // ------------------------------------------------------------------ plumbing

  @FunctionalInterface
  private interface Work<T> {
    T run() throws Exception;
  }

  /** Runs work with auto-commit off and the migration lock held, restoring state afterwards. */
  private static <T> T withTransactionAndLock(Connection connection, Work<T> work)
      throws Exception {
    boolean oldAutoCommit = connection.getAutoCommit();
    Exception failure = null;
    try {
      connection.setAutoCommit(false);
      try (AutoCloseable ignored = DatabaseMigrationLock.acquire(connection)) {
        return work.run();
      }
    } catch (Exception e) {
      failure = e;
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      throw e;
    } finally {
      try {
        connection.setAutoCommit(oldAutoCommit);
      } catch (SQLException restoreFailure) {
        if (failure != null) {
          failure.addSuppressed(restoreFailure);
        } else {
          throw restoreFailure;
        }
      }
    }
  }

  private record FailureRecord(String checksum, String state) {}
}
