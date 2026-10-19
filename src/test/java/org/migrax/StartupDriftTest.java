package org.migrax;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.runner.StartupMigrations;
import org.migrax.util.Json;
import org.migrax.util.Log;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** migrax.drift.check: the database compared with the packaged snapshot at startup. */
class StartupDriftTest {
  private static final String SCHEMA =
      "CREATE TABLE note (id INT PRIMARY KEY, body VARCHAR(100) NOT NULL);\n";
  private static int databases;

  @TempDir
  Path classes;

  private final List<String> warnings = new ArrayList<>();
  private final List<String> infos = new ArrayList<>();
  private Log.Sink previous;
  private JdbcDataSource dataSource;
  private URLClassLoader loader;

  @BeforeEach
  void setUp() throws Exception {
    previous = Log.setSink(new Log.Sink() {
      @Override
      public void debug(String message) {}

      @Override
      public void info(String message) {
        infos.add(message);
      }

      @Override
      public void warn(String message) {
        warnings.add(message);
      }

      @Override
      public void error(String message) {
        warnings.add(message);
      }
    });
    Path folder = Files.createDirectories(classes.resolve("db/migration"));
    Files.writeString(folder.resolve("0001_note.sql"), SCHEMA);
    dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:startup_drift_" + (++databases) + ";DB_CLOSE_DELAY=-1");
    loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        getClass().getClassLoader());
  }

  @AfterEach
  void tearDown() throws Exception {
    Log.setSink(previous);
    loader.close();
  }

  /** Packages the snapshot the migrations produce, as the build does. */
  private void packageSnapshot(String resource) throws Exception {
    JdbcDataSource scratch = new JdbcDataSource();
    scratch.setURL("jdbc:h2:mem:startup_drift_snapshot_" + (++databases) + ";DB_CLOSE_DELAY=-1");
    try (Connection connection = scratch.getConnection()) {
      connection.createStatement().execute(SCHEMA);
      Path file = classes.resolve(resource);
      Files.createDirectories(file.getParent());
      Files.writeString(file, Json.write(DatabaseSchemaReader.read(connection), "h2"));
    }
  }

  private void execute(String sql) throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      connection.createStatement().execute(sql);
    }
  }

  private List<?> check(String mode) throws Exception {
    return StartupMigrations.checkDrift(dataSource, Map.of("migrax.drift.check", mode), loader,
        null);
  }

  @Test
  void offIsTheDefaultAndReadsNothing() throws Exception {
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    execute("ALTER TABLE note ADD COLUMN extra INT");
    assertEquals(List.of(), StartupMigrations.checkDrift(dataSource, Map.of(), loader, null));
    assertEquals(List.of(), check("off"));
    assertEquals(List.of(), warnings);
  }

  @Test
  void aMatchingDatabaseHasNoDrift() throws Exception {
    packageSnapshot("META-INF/migrax/snapshot.json");
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    assertEquals(List.of(), check("fail"));
    assertTrue(infos.stream().anyMatch(line -> line.contains("no drift")), infos.toString());
  }

  @Test
  void warnLogsEveryDifference() throws Exception {
    packageSnapshot("META-INF/migrax/snapshot.json");
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    execute("ALTER TABLE note ADD COLUMN extra INT");
    execute("CREATE TABLE manual (id INT)");

    assertEquals(2, check("warn").size());
    String warning = String.join("\n", warnings);
    assertTrue(warning.contains("2 difference(s)"), warning);
    assertTrue(warning.contains("MANUAL") || warning.contains("manual"), warning);
    assertTrue(warning.toLowerCase().contains("extra"), warning);
  }

  @Test
  void failStopsTheStartWithTheDifferences() throws Exception {
    packageSnapshot("META-INF/migrax/snapshot.json");
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    execute("ALTER TABLE note DROP COLUMN body");

    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> check("FAIL"));
    assertTrue(error.getMessage().contains("1 difference(s)"), error.getMessage());
    assertTrue(error.getMessage().toLowerCase().contains("body"), error.getMessage());
    assertTrue(error.getMessage().contains("migrax.drift.check=fail stops the start"),
        error.getMessage());
  }

  @Test
  void aMissingSnapshotIsExplained() throws Exception {
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    assertEquals(List.of(), check("warn"));
    assertTrue(warnings.get(0).contains("META-INF/migrax/snapshot.json is not in the "
        + "application"), warnings.toString());
    IllegalStateException error = assertThrows(IllegalStateException.class,
        () -> check("fail"));
    assertTrue(error.getMessage().contains("can't check for drift"), error.getMessage());
  }

  @Test
  void pendingMigrationsSkipTheCheck() throws Exception {
    packageSnapshot("META-INF/migrax/snapshot.json");
    assertEquals(List.of(), check("fail"));
    assertTrue(infos.stream().anyMatch(line -> line.contains("drift check skipped: 1 "
        + "migration(s) pending")), infos.toString());
  }

  @Test
  void aFurtherDatabaseReadsItsOwnSnapshot() throws Exception {
    packageSnapshot("META-INF/migrax/billing/snapshot.json");
    StartupMigrations.migrate(dataSource, Map.of(), loader);
    assertEquals(List.of(), StartupMigrations.checkDrift(dataSource,
        Map.of("migrax.drift.check", "fail"), loader, "billing"));
    assertThrows(IllegalStateException.class, () -> check("fail"));
  }

  /** What the build plugins and the Quarkus build step put into the application. */
  @Test
  void theBuildPackagesEachDatabasesSnapshotButNotTheHistory() throws Exception {
    Path project = classes.resolve("project");
    Path state = project.resolve(".migrax");
    for (String file : List.of("snapshot.json", "billing/snapshot.json",
        "history/0001_init.json", "history/snapshot.json", "pending/snapshot.json",
        "not a name/snapshot.json")) {
      Files.createDirectories(state.resolve(file).getParent());
      Files.writeString(state.resolve(file), "{}");
    }
    assertEquals(Map.of(
        "META-INF/migrax/snapshot.json", state.resolve("snapshot.json"),
        "META-INF/migrax/billing/snapshot.json", state.resolve("billing/snapshot.json")),
        org.migrax.diff.SnapshotStore.packaged(project));
    assertEquals(Map.of(), org.migrax.diff.SnapshotStore.packaged(classes.resolve("empty")));
  }

  @Test
  void anUnknownModeIsRejected() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> check("sometimes"));
    assertEquals("migrax.drift.check=sometimes is not a mode: use off, warn or fail.",
        error.getMessage());
  }
}
