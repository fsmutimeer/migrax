package io.migrax.importer;

import io.migrax.dialect.Dialect;
import io.migrax.diff.DiffEngine;
import io.migrax.model.SchemaModel;
import io.migrax.plugin.DatabaseSchemaReader;
import io.migrax.runner.Migration;
import io.migrax.runner.MigrationLoader;
import io.migrax.runner.MigrationRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adopts databases managed by Flyway or Liquibase, so a team can switch to Migrax with one
 * command and without re-running anything.
 *
 * @since 0.1.0
 */
public final class Importer {
  private static final Pattern FLYWAY_VERSION = Pattern.compile("^[vV]([0-9]+(?:[._][0-9]+)*)__");

  private Importer() {}

  /** What an import did. */
  public record Report(int recorded, List<String> notes, Path createdFile) {
    public Report {
      notes = List.copyOf(notes);
    }
  }

  /**
   * Records every migration Flyway applied successfully as applied in Migrax's history. Flyway's
   * {@code V1__init.sql} and {@code R__views.sql} files keep working unchanged: Migrax orders
   * them by version and re-runs repeatable files when they change.
   */
  public static Report flyway(Connection connection, Path folder, String historyTable)
      throws Exception {
    String table = historyTable == null || historyTable.isBlank()
        ? "flyway_schema_history" : historyTable;
    List<String[]> rows = readFlyway(connection, table);
    List<Path> files = MigrationLoader.sqlFiles(folder);
    List<Migration> toRecord = new ArrayList<>();
    List<String> notes = new ArrayList<>();
    String baseline = null;
    for (String[] row : rows) {
      String version = row[0];
      String type = row[1] == null ? "" : row[1].toUpperCase(Locale.ROOT);
      String script = row[2];
      if (type.contains("BASELINE")) {
        baseline = version;
        continue;
      }
      if (type.startsWith("JDBC") || type.startsWith("SPRING_JDBC")) {
        String simpleName = script.substring(script.lastIndexOf('.') + 1);
        toRecord.add(new Migration(simpleName, Migration.Kind.VERSIONED, null,
            "java:" + script, null));
        continue;
      }
      String fileName = script.substring(Math.max(script.lastIndexOf('/'), script.lastIndexOf('\\')) + 1);
      Path file = folder.resolve(fileName);
      if (!Files.isRegularFile(file)) {
        notes.add("Skipped " + script + ": the file is not in " + folder + ".");
        continue;
      }
      toRecord.add(Migration.load(file));
    }
    if (baseline != null) {
      for (Path file : files) {
        String name = file.getFileName().toString();
        Matcher matcher = FLYWAY_VERSION.matcher(name);
        if (matcher.find() && compareVersions(matcher.group(1), baseline) <= 0) {
          toRecord.add(Migration.load(file));
        }
      }
      notes.add("Flyway baseline " + baseline + ": files up to that version are recorded as "
          + "applied.");
    }
    int recorded = new MigrationRunner().markApplied(connection, dedupe(toRecord));
    return new Report(recorded, notes, null);
  }

  /**
   * Adopts a Liquibase-managed database: writes a baseline migration with the current schema
   * (so new environments can be created from it) and records it as applied here.
   */
  public static Report liquibase(Connection connection, Dialect dialect, Path folder,
                                 String baselineName, String schema) throws Exception {
    return liquibase(connection, dialect, folder, baselineName, schema, null);
  }

  /**
   * Like {@link #liquibase(Connection, Dialect, Path, String, String)}, reading the database as
   * the first {@code generate} does: with the entities, the baseline also has the sequences
   * (and their sizes) that the entities use.
   *
   * @param entities the project's entity model, or null to read the tables only
   * @since 0.2.0
   */
  public static Report liquibase(Connection connection, Dialect dialect, Path folder,
                                 String baselineName, String schema, SchemaModel entities)
      throws Exception {
    int changeSets = count(connection, "databasechangelog");
    if (changeSets < 0) {
      throw new IllegalStateException("No DATABASECHANGELOG table found; this database does "
          + "not look like it is managed by Liquibase.");
    }
    SchemaModel current = entities == null ? DatabaseSchemaReader.read(connection, schema)
        : io.migrax.diff.DatabaseBaseline.read(connection, schema, entities);
    Path file = folder.resolve(baselineName.endsWith(".sql") ? baselineName : baselineName + ".sql");
    if (Files.exists(file)) {
      throw new IllegalStateException("Migration already exists: " + file);
    }
    StringBuilder sql = new StringBuilder()
        .append("-- Baseline imported from Liquibase on ").append(LocalDate.now())
        .append(" (").append(changeSets).append(" change sets).\n")
        .append("-- Existing databases record this migration as applied without running it;\n")
        .append("-- new databases create the schema from it. Review the types before use.\n\n");
    for (var operation : dialect.prepare(new DiffEngine().diff(SchemaModel.empty(), current),
        SchemaModel.empty(), current)) {
      sql.append(dialect.render(operation)).append(";\n");
    }
    Files.createDirectories(folder);
    Files.writeString(file, sql);
    int recorded = new MigrationRunner().markApplied(connection, List.of(Migration.load(file)));
    List<String> notes = new ArrayList<>();
    notes.add("Wrote " + file.getFileName() + " with " + current.tables().size()
        + " table(s) from the current schema.");
    return new Report(recorded, notes, file);
  }

  private static List<Migration> dedupe(List<Migration> migrations) {
    List<Migration> unique = new ArrayList<>();
    java.util.Set<String> seen = new java.util.HashSet<>();
    for (Migration migration : migrations) {
      if (seen.add(migration.version())) {
        unique.add(migration);
      }
    }
    return unique;
  }

  private static List<String[]> readFlyway(Connection connection, String table)
      throws SQLException {
    SQLException first = null;
    // Flyway quotes its table and column names, which matters on H2 and Oracle.
    String quotedColumns = "SELECT \"version\", \"type\", \"script\", \"success\" FROM \""
        + table + "\" ORDER BY \"installed_rank\"";
    String plainColumns = "SELECT version, type, script, success FROM " + table
        + " ORDER BY installed_rank";
    for (String query : List.of(plainColumns, quotedColumns)) {
      List<String[]> rows = new ArrayList<>();
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery(query)) {
        while (result.next()) {
          if (result.getBoolean(4)) {
            rows.add(new String[]{result.getString(1), result.getString(2), result.getString(3)});
          }
        }
        return rows;
      } catch (SQLException e) {
        if (first == null) {
          first = e;
        }
        if (!connection.getAutoCommit()) {
          connection.rollback();
        }
      }
    }
    throw new SQLException("Could not read Flyway's " + table + " table: "
        + (first == null ? "" : first.getMessage()), first);
  }

  private static int count(Connection connection, String table) {
    for (String name : List.of(table, table.toUpperCase(Locale.ROOT), "\"" + table + "\"")) {
      try (Statement statement = connection.createStatement();
           ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + name)) {
        return result.next() ? result.getInt(1) : 0;
      } catch (SQLException e) {
        // Try the next spelling.
      }
    }
    return -1;
  }

  /** Compares Flyway versions such as 1.10 and 1.2 numerically, part by part. */
  static int compareVersions(String a, String b) {
    String[] left = a.split("[._]");
    String[] right = b.split("[._]");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      long x = i < left.length ? Long.parseLong(left[i]) : 0;
      long y = i < right.length ? Long.parseLong(right[i]) : 0;
      if (x != y) {
        return Long.compare(x, y);
      }
    }
    return 0;
  }
}
