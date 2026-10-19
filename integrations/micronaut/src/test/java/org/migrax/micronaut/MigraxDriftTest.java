package org.migrax.micronaut;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.util.Json;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** migrax.drift.check: the database compared with the packaged snapshot at startup. */
class MigraxDriftTest {
  private static final String URL = "jdbc:h2:mem:migrax_micronaut_drift;DB_CLOSE_DELAY=-1";

  @TempDir
  Path classes;

  private static void start(ClassLoader loader, String mode) {
    try (ApplicationContext context = ApplicationContext.builder().classLoader(loader)
        .properties(Map.of("datasources.default.url", URL, "migrax.drift.check", mode))
        .start()) {
      context.getBean(DataSource.class);
    }
  }

  private static void execute(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
      connection.createStatement().execute(sql);
    }
  }

  @Test
  void handMadeChangesWarnOrStopTheStart() throws Exception {
    ClassLoader plain = getClass().getClassLoader();
    start(plain, "off");
    Path snapshot = classes.resolve("META-INF/migrax/snapshot.json");
    Files.createDirectories(snapshot.getParent());
    try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
      Files.writeString(snapshot, Json.write(DatabaseSchemaReader.read(connection), "h2"));
    }
    try (URLClassLoader packaged = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        plain)) {
      start(packaged, "fail");

      execute("CREATE TABLE made_by_hand (id INT)");
      start(packaged, "warn");
      Exception error = assertThrows(Exception.class, () -> start(packaged, "fail"));
      String messages = messages(error);
      assertTrue(messages.contains("Migrax: 1 difference(s)"), messages);
      assertTrue(messages.toLowerCase().contains("made_by_hand"), messages);
    } finally {
      execute("DROP TABLE IF EXISTS made_by_hand");
    }
  }

  private static String messages(Throwable error) {
    StringBuilder all = new StringBuilder();
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      all.append(cause.getMessage()).append('\n');
    }
    return all.toString();
  }
}
