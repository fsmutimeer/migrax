package io.migrax.runner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class MigrationRunner {
  public int migrate(Connection connection, List<Path> files) throws Exception {
    return migrate(connection, files, false);
  }

  private static void verifyFailedAttempts(
      Connection connection, List<MigrationFile> migrations, boolean resume) throws SQLException {
    java.util.Map<String, MigrationFile> available = migrations.stream().collect(
        java.util.stream.Collectors.toMap(MigrationFile::version, migration -> migration));
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery(
             "SELECT version, checksum, failure_state FROM migrax_failures")) {
      while (result.next()) {
        String version = result.getString(1);
        MigrationFile migration = available.get(version);
        if (migration == null) {
          continue;
        }
        if (!migration.checksum().equals(result.getString(2))) {
          throw new IllegalStateException(
              "Failed migration '" + version + "' was edited after failure. Inspect the database "
                  + "and use the explicit repair goal before changing or retrying this file.");
        }
        if (!resume) {
          throw new IllegalStateException(
              "Migration '" + version + "' has an incomplete/failed attempt. Inspect the database "
                  + "and use repair, or request guarded resume if the migration is marked resume-safe.");
        }
        if (!isResumeSafe(migration.sql())) {
          throw new IllegalStateException(
              "Migration '" + version + "' is not marked with '-- migrax:resume-safe'. "
                  + "Do not automatically replay arbitrary SQL after a partial failure.");
        }
      }
    }
  }

  public int migrate(Connection connection, List<Path> files, boolean resume) throws Exception {
    boolean oldAutoCommit = connection.getAutoCommit();
    Exception failure = null;
    int appliedCount = 0;
    try {
      connection.setAutoCommit(false);
      try (AutoCloseable ignored = DatabaseMigrationLock.acquire(connection)) {
        ensureHistory(connection);
        ensureFailureTable(connection);
        verifyAppliedFilesExist(connection, files);
        List<MigrationFile> migrations = validateFiles(connection, files);
        verifyAppliedChecksums(connection, migrations);
        verifyFailedAttempts(connection, migrations, resume);
        for (MigrationFile migration : migrations) {
          if (applyMigration(connection, migration, resume)) {
            appliedCount++;
          }
        }
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
    return appliedCount;
  }

  private static List<MigrationFile> validateFiles(Connection connection, List<Path> files)
      throws Exception {
    List<MigrationFile> migrations = new ArrayList<>();
    java.util.Set<String> versions = new java.util.HashSet<>();
    String databaseProduct = connection.getMetaData().getDatabaseProductName()
        .toLowerCase(java.util.Locale.ROOT);
    boolean backslashEscapes = databaseProduct.contains("mysql") || databaseProduct.contains("mariadb");
    if (backslashEscapes) {
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
        if (result.next()) {
          backslashEscapes = !result.getString(1).toUpperCase(java.util.Locale.ROOT)
              .contains("NO_BACKSLASH_ESCAPES");
        }
      }
    }
    for (Path file : files.stream().sorted().toList()) {
      if (!Files.isRegularFile(file)) {
        throw new IllegalArgumentException("Migration is not a regular file: " + file);
      }
      String version = file.getFileName().toString();
      if (!version.matches("[A-Za-z0-9][A-Za-z0-9_.-]*\\.sql")) {
        throw new IllegalArgumentException("Invalid migration filename: " + version);
      }
      if (!versions.add(version.toLowerCase(java.util.Locale.ROOT))) {
        throw new IllegalArgumentException("Duplicate migration filename: " + version);
      }
      String sql = Files.readString(file, StandardCharsets.UTF_8);
      migrations.add(new MigrationFile(version, sql, sha256(sql),
          splitStatements(sql, backslashEscapes)));
    }
    return List.copyOf(migrations);
  }

  private static void verifyAppliedChecksums(
      Connection connection, List<MigrationFile> migrations) throws SQLException {
    java.util.Map<String, String> checksums = migrations.stream().collect(
        java.util.stream.Collectors.toMap(MigrationFile::version, MigrationFile::checksum));
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version, checksum FROM migrax_history")) {
      while (result.next()) {
        String version = result.getString(1);
        String actual = checksums.get(version);
        if (actual == null) {
          continue;
        }
        if (!actual.equals(result.getString(2))) {
          throw new IllegalStateException("Checksum changed for applied migration " + version);
        }
      }
    }
  }

  private static void verifyAppliedFilesExist(Connection connection, List<Path> files)
      throws SQLException {
    java.util.Set<String> available = files.stream()
        .map(path -> path.getFileName().toString())
        .collect(java.util.stream.Collectors.toSet());
    List<String> missing = new ArrayList<>();
    try (Statement statement = connection.createStatement();
         ResultSet result = statement.executeQuery("SELECT version FROM migrax_history")) {
      while (result.next()) {
        String version = result.getString(1);
        if (!available.contains(version)) {
          missing.add(version);
        }
      }
    }
    try (Statement failureStatement = connection.createStatement();
         ResultSet failureResult = failureStatement.executeQuery("SELECT version FROM migrax_failures")) {
      while (failureResult.next()) {
        String version = failureResult.getString(1);
        if (!available.contains(version)) {
          missing.add(version);
        }
      }
    }
    if (!missing.isEmpty()) {
      missing.sort(String::compareTo);
      throw new IllegalStateException(
          "Applied migration file(s) are missing from the configured migration location: "
              + String.join(", ", missing)
              + ". Restore the files from version control; applied migrations must not be deleted.");
    }
  }

  private boolean applyMigration(Connection connection, MigrationFile migration, boolean resume)
      throws Exception {
    String version = migration.version();
    String sql = migration.sql();
    String checksum = migration.checksum();
    try (PreparedStatement query =
             connection.prepareStatement("SELECT checksum FROM migrax_history WHERE version=?")) {
      query.setString(1, version);
      try (ResultSet result = query.executeQuery()) {
        if (result.next()) {
          if (!result.getString(1).equals(checksum)) {
            throw new IllegalStateException("Checksum changed for applied migration " + version);
          }
          connection.commit();
          return false;
        }
      }
    }

    FailureRecord existingFailure = findFailure(connection, version);
    if (existingFailure != null) {
      if (!existingFailure.checksum().equals(checksum)) {
        throw new IllegalStateException(
            "Failed migration '" + version + "' was edited after failure. Inspect the database "
                + "and use the explicit repair goal before changing or retrying this file.");
      }
      if (!resume) {
        throw new IllegalStateException(
            "Migration '" + version + "' has an incomplete/failed attempt. Inspect the database "
                + "and use the explicit repair goal, or request guarded resume if the migration "
                + "is marked resume-safe.");
      }
      if (!isResumeSafe(sql)) {
        throw new IllegalStateException(
            "Migration '" + version + "' is not marked with '-- migrax:resume-safe'. "
                + "Do not automatically replay arbitrary SQL after a partial failure.");
      }
      setFailureState(connection, version, checksum, "RUNNING",
          "Retry requested explicitly; migration is marked resume-safe.");
    } else {
      setFailureState(connection, version, checksum, "RUNNING", null);
    }
    connection.commit();

    try {
      try (Statement statement = connection.createStatement()) {
        for (String command : migration.statements()) {
          if (!command.isBlank()) {
            statement.execute(command);
          }
        }
      }
      try (PreparedStatement insert =
               connection.prepareStatement(
                   "INSERT INTO migrax_history(version,checksum,applied_at) "
                       + "VALUES(?,?," + currentTimestamp(connection) + ")")) {
        insert.setString(1, version);
        insert.setString(2, checksum);
        insert.executeUpdate();
      }
      deleteFailure(connection, version);
      connection.commit();
      return true;
    } catch (Exception e) {
      try {
        connection.rollback();
      } catch (SQLException rollbackFailure) {
        e.addSuppressed(rollbackFailure);
      }
      try {
        setFailureState(connection, version, checksum, "FAILED",
            e.getClass().getSimpleName() + " while executing migration.");
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

  private void ensureHistory(Connection connection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    if (!tableExists(metadata, connection.getCatalog(), "MIGRAX_HISTORY")
        && !tableExists(metadata, connection.getCatalog(), "migrax_history")) {
      String timestampType = timestampType(connection);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE migrax_history (version VARCHAR(255) PRIMARY KEY, "
                + "checksum VARCHAR(64) NOT NULL, "
                + "applied_at " + timestampType + " NOT NULL)");
      }
    }
  }

  private void ensureFailureTable(Connection connection) throws SQLException {
    DatabaseMetaData metadata = connection.getMetaData();
    if (!tableExists(metadata, connection.getCatalog(), "MIGRAX_FAILURES")
        && !tableExists(metadata, connection.getCatalog(), "migrax_failures")) {
      String timestampType = timestampType(connection);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate(
            "CREATE TABLE migrax_failures (version VARCHAR(255) PRIMARY KEY, "
                + "checksum VARCHAR(64) NOT NULL, failure_state VARCHAR(16) NOT NULL, "
                + "attempt_count INTEGER NOT NULL, failure_message VARCHAR(2000), "
                + "updated_at " + timestampType + " NOT NULL)");
      }
    }
  }

  private static boolean tableExists(DatabaseMetaData metadata, String catalog, String name)
      throws SQLException {
    try (ResultSet tables = metadata.getTables(catalog, null, name, new String[]{"TABLE"})) {
      if (tables.next()) {
        return true;
      }
    }
    try (ResultSet tables = metadata.getTables(
        catalog, null, name.toUpperCase(java.util.Locale.ROOT), new String[]{"TABLE"})) {
      return tables.next();
    }
  }

  private static String timestampType(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName()
        .toLowerCase(java.util.Locale.ROOT);
    return product.contains("microsoft sql server") ? "DATETIME2" : "TIMESTAMP";
  }

  private static String currentTimestamp(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName()
        .toLowerCase(java.util.Locale.ROOT);
    if (product.contains("microsoft sql server")) {
      return "SYSUTCDATETIME()";
    }
    if (product.contains("oracle")) {
      return "SYSTIMESTAMP";
    }
    return "CURRENT_TIMESTAMP";
  }

  public void repair(Connection connection, Path migrationFile, String action, boolean confirm)
      throws Exception {
    if (!confirm) {
      throw new IllegalArgumentException(
          "Repair changes migration history. Pass explicit confirmation after inspecting the database.");
    }
    String normalizedAction = action == null ? "" : action.trim().toLowerCase(java.util.Locale.ROOT);
    if (!normalizedAction.equals("applied") && !normalizedAction.equals("retry")) {
      throw new IllegalArgumentException("Repair action must be 'applied' or 'retry'.");
    }
    String version = migrationFile.getFileName().toString();
    String sql = Files.readString(migrationFile, StandardCharsets.UTF_8);
    String checksum = sha256(sql);
    boolean oldAutoCommit = connection.getAutoCommit();
    Exception failure = null;
    try {
      connection.setAutoCommit(false);
      try (AutoCloseable ignored = DatabaseMigrationLock.acquire(connection)) {
        ensureHistory(connection);
        ensureFailureTable(connection);
        FailureRecord failed = findFailure(connection, version);
        if (failed == null) {
          throw new IllegalStateException(
              "No incomplete migration record exists for " + version + "; nothing to repair.");
        }
        if (!failed.checksum().equals(checksum)) {
          throw new IllegalStateException(
              "The migration checksum differs from the failed attempt. Restore the original file "
                  + "before repairing migration history.");
        }
        if (normalizedAction.equals("applied")) {
          try (PreparedStatement insert = connection.prepareStatement(
              "INSERT INTO migrax_history(version,checksum,applied_at) "
                  + "VALUES(?,?," + currentTimestamp(connection) + ")")) {
            insert.setString(1, version);
            insert.setString(2, checksum);
            insert.executeUpdate();
          }
        }
        deleteFailure(connection, version);
        connection.commit();
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

  private static FailureRecord findFailure(Connection connection, String version)
      throws SQLException {
    try (PreparedStatement query = connection.prepareStatement(
        "SELECT checksum, failure_state FROM migrax_failures WHERE version=?")) {
      query.setString(1, version);
      try (ResultSet result = query.executeQuery()) {
        return result.next()
            ? new FailureRecord(result.getString(1), result.getString(2))
            : null;
      }
    }
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

  private static boolean isResumeSafe(String sql) {
    return sql.lines().anyMatch(line ->
        line.trim().equalsIgnoreCase("-- migrax:resume-safe"));
  }

  private static List<String> splitStatements(String sql, boolean backslashEscapes) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    String dollarQuote = null;
    boolean lineComment = false;
    boolean blockComment = false;

    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

      if (lineComment) {
        current.append(ch);
        if (ch == '\n' || ch == '\r') {
          lineComment = false;
        }
        continue;
      }
      if (blockComment) {
        current.append(ch);
        if (ch == '*' && next == '/') {
          current.append(next);
          i++;
          blockComment = false;
        }
        continue;
      }
      if (dollarQuote != null) {
        if (sql.startsWith(dollarQuote, i)) {
          current.append(dollarQuote);
          i += dollarQuote.length() - 1;
          dollarQuote = null;
        } else {
          current.append(ch);
        }
        continue;
      }
      if (quote != 0) {
        current.append(ch);
        if (backslashEscapes && ch == '\\' && quote == '\'' && next != 0) {
          current.append(next);
          i++;
        } else if (ch == quote) {
          if (next == quote || (quote == ']' && next == ']')) {
            current.append(next);
            i++;
          } else {
            quote = 0;
          }
        }
        continue;
      }

      if (ch == '-' && next == '-') {
        current.append(ch).append(next);
        i++;
        lineComment = true;
      } else if (ch == '/' && next == '*') {
        current.append(ch).append(next);
        i++;
        blockComment = true;
      } else if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
        current.append(ch);
        quote = ch == '[' ? ']' : ch;
      } else if (ch == '$') {
        int delimiterEnd = sql.indexOf('$', i + 1);
        if (delimiterEnd >= 0) {
          String candidate = sql.substring(i, delimiterEnd + 1);
          if (candidate.matches("\\$[A-Za-z0-9_]*\\$")) {
            dollarQuote = candidate;
            current.append(candidate);
            i = delimiterEnd;
          } else {
            current.append(ch);
          }
        } else {
          current.append(ch);
        }
      } else if (ch == ';') {
        addStatement(statements, current);
      } else {
        current.append(ch);
      }
    }
    addStatement(statements, current);
    if (quote != 0 || blockComment || dollarQuote != null) {
      throw new IllegalArgumentException("Unterminated quote or comment in SQL migration.");
    }
    return statements;
  }

  private static void addStatement(List<String> statements, StringBuilder current) {
    String statement = current.toString().trim();
    if (!statement.isEmpty()) {
      statements.add(statement);
    }
    current.setLength(0);
  }

  private static String sha256(String value) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
  }

  private record MigrationFile(
      String version, String sql, String checksum, List<String> statements) {}

  private record FailureRecord(String checksum, String state) {}
}
