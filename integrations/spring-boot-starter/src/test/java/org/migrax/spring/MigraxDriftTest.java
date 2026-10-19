package org.migrax.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.migrax.plugin.DatabaseSchemaReader;
import org.migrax.spring.app.TestApp;
import org.migrax.util.Json;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.DefaultResourceLoader;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** migrax.drift.check: the database compared with the packaged snapshot at startup. */
class MigraxDriftTest {
  private static final String URL = "jdbc:h2:mem:starter_drift;DB_CLOSE_DELAY=-1";

  @TempDir
  Path classes;

  private static ConfigurableApplicationContext start(ClassLoader loader, String... extra) {
    String[] properties = new String[extra.length + 3];
    properties[0] = "spring.datasource.url=" + URL;
    properties[1] = "spring.jpa.hibernate.ddl-auto=validate";
    properties[2] = "spring.main.web-application-type=none";
    System.arraycopy(extra, 0, properties, 3, extra.length);
    return new SpringApplicationBuilder(TestApp.class).properties(properties)
        .resourceLoader(new DefaultResourceLoader(loader)).run();
  }

  private static void execute(String sql) throws Exception {
    try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
      connection.createStatement().execute(sql);
    }
  }

  @Test
  void handMadeChangesWarnOrStopTheStart() throws Exception {
    ClassLoader plain = getClass().getClassLoader();
    try (ConfigurableApplicationContext ignored = start(plain)) {
      // Migrates; the snapshot is the schema the migrations produced.
    }
    Path snapshot = classes.resolve("META-INF/migrax/snapshot.json");
    Files.createDirectories(snapshot.getParent());
    try (Connection connection = DriverManager.getConnection(URL, "sa", "")) {
      Files.writeString(snapshot, Json.write(DatabaseSchemaReader.read(connection), "h2"));
    }
    try (URLClassLoader packaged = new URLClassLoader(new URL[] {classes.toUri().toURL()},
        plain)) {
      try (ConfigurableApplicationContext ignored = start(packaged,
          "migrax.drift.check=fail")) {
        // No drift: the application starts.
      }

      execute("ALTER TABLE book ADD COLUMN made_by_hand INT");
      try (ConfigurableApplicationContext ignored = start(packaged,
          "migrax.drift.check=warn")) {
        // warn only logs.
      }
      Exception error = assertThrows(Exception.class,
          () -> start(packaged, "migrax.drift.check=fail").close());
      String messages = messages(error);
      assertTrue(messages.contains("Migrax: 1 difference(s)"), messages);
      assertTrue(messages.toLowerCase().contains("made_by_hand"), messages);

      // Without a packaged snapshot, fail says what to do.
      Exception missing = assertThrows(Exception.class,
          () -> start(plain, "migrax.drift.check=fail").close());
      assertTrue(messages(missing).contains("META-INF/migrax/snapshot.json is not in the "
          + "application"), messages(missing));
    } finally {
      execute("ALTER TABLE book DROP COLUMN IF EXISTS made_by_hand");
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
