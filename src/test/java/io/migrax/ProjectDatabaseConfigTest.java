package io.migrax;

import io.migrax.plugin.ProjectDatabaseConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProjectDatabaseConfigTest {
  @TempDir
  Path resources;

  @Test
  void readsSpringYamlDatasourceConfiguration() throws Exception {
    Files.writeString(resources.resolve("application.yml"), """
        spring:
          datasource:
            url: jdbc:mysql://localhost:3306/orders
            username: app
            password: secret
        """);

    var credentials = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("jdbc:mysql://localhost:3306/orders", credentials.url());
    assertEquals("app", credentials.user());
    assertEquals("secret", credentials.password());
  }

  @Test
  void readsQuarkusYamlDatasourceConfiguration() throws Exception {
    Files.writeString(resources.resolve("application.yaml"), """
        quarkus:
          datasource:
            jdbc:
              url: jdbc:mysql://localhost:3306/orders
            username: app
            password: secret
        """);

    var credentials = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("jdbc:mysql://localhost:3306/orders", credentials.url());
    assertEquals("app", credentials.user());
    assertEquals("secret", credentials.password());
  }

  @Test
  void readsQuarkusPropertiesAndResolvesEnvironmentPlaceholders() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/orders
        quarkus.datasource.username=${MIGRAX_TEST_USER:orders}
        quarkus.datasource.password=secret
        """);

    var credentials = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("jdbc:postgresql://localhost:5432/orders", credentials.url());
    assertEquals("orders", credentials.user());
    assertEquals("secret", credentials.password());
  }

  @Test
  void resolvesSystemPropertiesInConfigurationPlaceholders() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=jdbc:mysql://localhost:3306/${migrax.test.database:orders}
        """);
    System.setProperty("migrax.test.database", "test-orders");
    try {
      var credentials = ProjectDatabaseConfig.load(resources, Map.of());
      assertEquals("jdbc:mysql://localhost:3306/test-orders", credentials.url());
    } finally {
      System.clearProperty("migrax.test.database");
    }
  }

  @Test
  void explicitMigraxSettingsOverrideApplicationConfiguration() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=jdbc:mysql://localhost:3306/from-config
        spring.datasource.username=config-user
        """);

    var credentials = ProjectDatabaseConfig.load(resources, Map.of(
        "url", "jdbc:mysql://localhost:3306/override",
        "user", "override-user",
        "password", ""));

    assertEquals("jdbc:mysql://localhost:3306/override", credentials.url());
    assertEquals("override-user", credentials.user());
  }

  @Test
  void defaultsMigrationLocationToFlywayStyleClasspathDirectory() throws Exception {
    var settings = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("classpath:db/migration", settings.locations());
    assertEquals(resources.resolve("db/migration"),
        ProjectDatabaseConfig.resolveMigrationDirectory(
            settings.locations(), resources.getParent(), resources));
  }

  @Test
  void springBootDefaultsToClasspathMigrationDirectory() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=jdbc:postgresql://localhost:5432/orders
        spring.datasource.username=app
        """);

    var settings = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("classpath:db/migration", settings.locations());
    assertEquals(resources.resolve("db/migration"),
        ProjectDatabaseConfig.resolveMigrationDirectory(
            settings.locations(), resources.getParent(), resources));
  }

  @Test
  void quarkusDefaultsToClasspathMigrationDirectory() throws Exception {
    Files.writeString(resources.resolve("application.yml"), """
        quarkus:
          datasource:
            jdbc:
              url: jdbc:postgresql://localhost:5432/orders
        """);

    var settings = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("classpath:db/migration", settings.locations());
    assertEquals(resources.resolve("db/migration"),
        ProjectDatabaseConfig.resolveMigrationDirectory(
            settings.locations(), resources.getParent(), resources));
  }

  @Test
  void readsConfiguredMigrationLocationFromApplicationProperties() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        migrax.locations=filesystem:database/sql
        """);

    var settings = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals(resources.getParent().resolve("database/sql"), ProjectDatabaseConfig
        .resolveMigrationDirectory(settings.locations(), resources.getParent(), resources));
  }

  @Test
  void readsMigraxSettingsFromApplicationProperties() throws Exception {
    Files.writeString(resources.resolve("application.properties"), """
        migrax.url=jdbc:postgresql://localhost:5432/current
        migrax.locations=filesystem:current-sql
        """);

    var settings = ProjectDatabaseConfig.load(resources, Map.of());

    assertEquals("jdbc:postgresql://localhost:5432/current", settings.url());
    assertEquals("filesystem:current-sql", settings.locations());
  }

  @Test
  void acceptsFlywayClasspathLocationsAndRejectsUnsupportedLocationLists() {
    assertEquals(resources.resolve("custom/migrations"), ProjectDatabaseConfig
        .resolveMigrationDirectory(
            "classpath:custom/migrations", resources.getParent(), resources));
    assertThrows(IllegalArgumentException.class, () -> ProjectDatabaseConfig
        .resolveMigrationDirectory(
            "classpath:db/migration,classpath:extra", resources.getParent(), resources));
    assertThrows(IllegalArgumentException.class, () -> ProjectDatabaseConfig
        .resolveMigrationDirectory(
            "classpath:../../outside", resources.getParent(), resources));
  }

  @Test
  void sanitizesCredentialsInJdbcUrls() {
    assertEquals(
        "jdbc:postgresql://user:***@localhost:5432/db",
        io.migrax.plugin.RuntimeJdbc.sanitizeUrl("jdbc:postgresql://user:supersecret@localhost:5432/db"));
    assertEquals(
        "jdbc:mysql://localhost:3306/db?user=root&password=***",
        io.migrax.plugin.RuntimeJdbc.sanitizeUrl("jdbc:mysql://localhost:3306/db?user=root&password=supersecret"));
  }

  @Test
  void findsHibernateSchemaGenerationThatFightsWithMigrations() {
    Map<String, String> config = new java.util.LinkedHashMap<>();
    config.put("quarkus.hibernate-orm.schema-management.strategy", "update");
    config.put("%dev.spring.jpa.hibernate.ddl-auto", "create-drop");
    config.put("%test.quarkus.hibernate-orm.database.generation", "drop-and-create");
    config.put("hibernate.hbm2ddl.auto", "validate");

    assertEquals(java.util.List.of("quarkus.hibernate-orm.schema-management.strategy=update",
            "%dev.spring.jpa.hibernate.ddl-auto=create-drop"),
        ProjectDatabaseConfig.schemaGeneration(config));
  }
}
