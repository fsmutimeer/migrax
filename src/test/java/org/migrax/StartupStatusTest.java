package org.migrax;

import org.migrax.runner.StartupMigrations;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The read-only status the framework health checks report. */
class StartupStatusTest {
  @TempDir
  Path classes;

  @Test
  void reportsPendingMigrationsUntilTheyAreApplied() throws Exception {
    Path folder = Files.createDirectories(classes.resolve("db/migration"));
    Files.writeString(folder.resolve("0001_init.sql"), "CREATE TABLE note (id INT);\n");
    Files.writeString(folder.resolve("0002_more.sql"), "CREATE TABLE tag (id INT);\n");
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:startup_status;DB_CLOSE_DELAY=-1");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        getClass().getClassLoader())) {
      var before = StartupMigrations.status(dataSource, Map.of(), loader);
      assertEquals(new StartupMigrations.Status(0, 2, 0), before);
      assertFalse(before.upToDate());

      StartupMigrations.migrate(dataSource, Map.of(), loader);
      var after = StartupMigrations.status(dataSource, Map.of(), loader);
      assertEquals(new StartupMigrations.Status(2, 0, 0), after);
      assertTrue(after.upToDate());
    }
  }

  @Test
  void migrationsForOtherEnvironmentsAreNeitherPendingNorRun() throws Exception {
    Path folder = Files.createDirectories(classes.resolve("db/migration"));
    Files.writeString(folder.resolve("0001_init.sql"), "CREATE TABLE note (id INT);\n");
    Files.writeString(folder.resolve("0002_seed.sql"),
        "-- migrax:env dev, test\nINSERT INTO note VALUES (1);\n");
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:startup_env;DB_CLOSE_DELAY=-1");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        getClass().getClassLoader())) {
      StartupMigrations.migrate(dataSource, Map.of(), loader);
      var production = StartupMigrations.status(dataSource, Map.of(), loader);
      assertEquals(new StartupMigrations.Status(1, 0, 0), production);
      assertTrue(production.upToDate());
      try (var connection = dataSource.getConnection();
           var rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM note")) {
        rows.next();
        assertEquals(0, rows.getInt(1));
      }

      Map<String, String> dev = Map.of("migrax.env", "DEV");
      assertEquals(new StartupMigrations.Status(1, 1, 0),
          StartupMigrations.status(dataSource, dev, loader));
      assertEquals(1, StartupMigrations.migrate(dataSource, dev, loader));
      assertEquals(new StartupMigrations.Status(2, 0, 0),
          StartupMigrations.status(dataSource, dev, loader));
      // Applied once, it stays applied: a later run without the environment is not confused.
      assertEquals(new StartupMigrations.Status(2, 0, 0),
          StartupMigrations.status(dataSource, Map.of(), loader));
    }
  }
}
