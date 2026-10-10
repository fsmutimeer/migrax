package io.migrax;

import io.migrax.runner.StartupMigrations;
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
}
