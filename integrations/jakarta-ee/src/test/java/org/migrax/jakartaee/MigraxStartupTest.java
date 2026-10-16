package org.migrax.jakartaee;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigraxStartupTest {
  @Test
  void settingsComeFromTheFileThenTheEnvironmentThenSystemProperties() throws Exception {
    Path folder = Files.createTempDirectory("migrax-jakarta-ee");
    Files.writeString(folder.resolve("migrax.properties"), """
        migrax.locations=db/from-file
        migrax.datasource=java:app/FromFile
        migrax.schemas=a
        other.key=ignored
        """);
    Properties system = new Properties();
    system.setProperty("migrax.schemas", "from-system");
    try (URLClassLoader loader = new URLClassLoader(new URL[] {folder.toUri().toURL()}, null)) {
      Map<String, String> settings = MigraxStartup.settings(loader,
          Map.of("MIGRAX_DATASOURCE", "java:app/FromEnvironment",
              "MIGRAX_JAVA_PACKAGE", "app.migrations",
              "MIGRAX_PLACEHOLDERS_GREETING", "hello",
              "MIGRAX_METRICS_ENABLED", "false",
              "PATH", "/bin"), system);
      assertEquals(Map.of(
          "migrax.metrics.enabled", "false",
          "migrax.locations", "db/from-file",
          "migrax.datasource", "java:app/FromEnvironment",
          "migrax.java-package", "app.migrations",
          "migrax.placeholders.greeting", "hello",
          "migrax.schemas", "from-system"), settings);
    }
  }
}
