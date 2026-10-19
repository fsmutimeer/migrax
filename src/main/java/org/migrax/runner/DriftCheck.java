package org.migrax.runner;

import org.migrax.dialect.Dialect;
import org.migrax.dialect.Dialects;
import org.migrax.model.SchemaModel;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.util.Json;
import org.migrax.util.Log;
import org.migrax.verify.SchemaComparator;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Compares the database with the schema its migrations produce, when the application starts
 * ({@code migrax.drift.check}). The expected schema is the project's snapshot, which the build
 * packages as {@code META-INF/migrax/snapshot.json} ({@code META-INF/migrax/<name>/snapshot.json}
 * for a further database).
 */
final class DriftCheck {
  static final String SETTING = "migrax.drift.check";

  private DriftCheck() {}

  /** What to do: off (the default), warn or fail. */
  enum Mode { OFF, WARN, FAIL }

  static Mode mode(Map<String, String> settings) {
    String value = settings.get(SETTING);
    if (value == null || value.isBlank()) {
      return Mode.OFF;
    }
    try {
      return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(SETTING + "=" + value.trim()
          + " is not a mode: use off, warn or fail.");
    }
  }

  /** Where the build puts the snapshot of a database. */
  static String resource(String database) {
    return database == null || Databases.DEFAULT.equals(database)
        ? "META-INF/migrax/snapshot.json" : "META-INF/migrax/" + database + "/snapshot.json";
  }

  static List<SchemaComparator.Difference> check(
      DataSource dataSource, Map<String, String> settings, ClassLoader loader, String database,
      java.util.concurrent.Callable<StartupMigrations.Status> statusOf) throws Exception {
    Mode mode = mode(settings);
    if (mode == Mode.OFF) {
      return List.of();
    }
    Prefixes prefixes = new Prefixes(database);
    String resource = resource(database);
    String snapshot = read(loader, resource);
    if (snapshot == null) {
      return report(mode, prefixes, "can't check for drift: " + resource + " is not in the "
          + "application. Let the build package .migrax/snapshot.json (Guides > Production "
          + "safety > Drift at startup), or set " + SETTING + "=off.", List.of());
    }
    if (snapshot.contains("<<<<<<<") || snapshot.contains(">>>>>>>")) {
      return report(mode, prefixes, "can't check for drift: the packaged snapshot has git "
          + "merge conflicts. Run 'migrax merge' and build again.", List.of());
    }
    StartupMigrations.Status status = statusOf.call();
    if (!status.upToDate()) {
      // The differences would be what the waiting migrations change.
      Log.info("{}drift check skipped: {} migration(s) pending, {} with problems",
          prefixes.log(), status.pending(), status.problems());
      return List.of();
    }
    SchemaModel expected = Json.parse(snapshot);
    List<SchemaComparator.Difference> differences = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      String dialect = Json.dialect(snapshot);
      if (dialect == null) {
        dialect = Dialects.forConnection(connection).map(Dialect::id).orElse(null);
      }
      for (String schema : schemas(settings)) {
        for (SchemaComparator.Difference difference : SchemaComparator.compare(expected,
            DatabaseSchemaReader.read(connection, schema), dialect)) {
          differences.add(schema == null ? difference : new SchemaComparator.Difference(
              difference.kind(), schema + "." + difference.object(), difference.detail()));
        }
      }
    }
    if (differences.isEmpty()) {
      Log.info("{}no drift: the database matches the schema its migrations produce",
          prefixes.log());
      return differences;
    }
    StringBuilder message = new StringBuilder().append(differences.size())
        .append(" difference(s) between the database and the schema its migrations produce:");
    differences.forEach(difference -> message.append("\n  - ").append(difference));
    message.append("\nSomeone changed the database outside migrations. Write a migration for "
        + "intended changes, or revert them.");
    return report(mode, prefixes, message.toString(), differences);
  }

  /**
   * How a message starts: log lines get "Migrax: " from the framework's log adapter, so they
   * only name a further database; an exception names Migrax itself.
   */
  private record Prefixes(String log, String failure) {
    Prefixes(String database) {
      this(database == null || Databases.DEFAULT.equals(database) ? ""
              : "database " + database + ": ",
          database == null || Databases.DEFAULT.equals(database) ? "Migrax: "
              : "Migrax (" + database + "): ");
    }
  }

  private static List<SchemaComparator.Difference> report(
      Mode mode, Prefixes prefixes, String message,
      List<SchemaComparator.Difference> differences) {
    if (mode == Mode.FAIL) {
      throw new IllegalStateException(prefixes.failure() + message + "\n(" + SETTING
          + "=fail stops the start; warn only logs.)");
    }
    Log.warn("{}{}", prefixes.log(), message);
    return differences;
  }

  private static String read(ClassLoader loader, String resource) throws Exception {
    ClassLoader from = loader != null ? loader : DriftCheck.class.getClassLoader();
    try (InputStream in = from.getResourceAsStream(resource)) {
      return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** {@code migrax.schemas}, or one null for the connection's default schema. */
  private static List<String> schemas(Map<String, String> settings) {
    String value = settings.get("migrax.schemas");
    List<String> schemas = new ArrayList<>();
    if (value != null) {
      for (String schema : value.split(",")) {
        if (!schema.isBlank()) {
          schemas.add(schema.trim());
        }
      }
    }
    return schemas.isEmpty() ? Collections.singletonList(null) : schemas;
  }
}
