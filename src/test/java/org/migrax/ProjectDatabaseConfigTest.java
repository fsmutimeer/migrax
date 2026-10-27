package org.migrax;

import org.migrax.plugin.ProjectDatabaseConfig;
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
  void defaultsMigrationLocationToClasspathDirectory() throws Exception {
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
  void aLocationWithoutPrefixIsAClasspathFolderAsAtStartup() {
    Path project = resources.getParent();
    assertEquals(resources.resolve("db/sql"),
        ProjectDatabaseConfig.resolveMigrationDirectory("db/sql", project, resources));
    assertEquals(project.resolve("db/sql"),
        ProjectDatabaseConfig.resolveMigrationDirectory("filesystem:db/sql", project, resources));
    Path absolute = project.resolve("elsewhere").toAbsolutePath();
    assertEquals(absolute, ProjectDatabaseConfig.resolveMigrationDirectory(
        absolute.toString(), project, resources));
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
  void acceptsClasspathLocationsAndRejectsUnsupportedLocationLists() {
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
        org.migrax.plugin.RuntimeJdbc.sanitizeUrl("jdbc:postgresql://user:supersecret@localhost:5432/db"));
    assertEquals(
        "jdbc:mysql://localhost:3306/db?user=root&password=***",
        org.migrax.plugin.RuntimeJdbc.sanitizeUrl("jdbc:mysql://localhost:3306/db?user=root&password=supersecret"));
  }

  @Test
  void sanitizesOracleAndSqlServerPasswordForms() {
    java.util.function.UnaryOperator<String> clean = org.migrax.plugin.RuntimeJdbc::sanitizeUrl;
    assertEquals("jdbc:oracle:thin:scott/***@db:1521/FREEPDB1",
        clean.apply("jdbc:oracle:thin:scott/tiger@db:1521/FREEPDB1"));
    assertEquals("jdbc:oracle:thin:scott/***@//db:1521/FREEPDB1",
        clean.apply("jdbc:oracle:thin:scott/tiger@//db:1521/FREEPDB1"));
    // Without credentials nothing changes.
    assertEquals("jdbc:oracle:thin:@//db:1521/FREEPDB1",
        clean.apply("jdbc:oracle:thin:@//db:1521/FREEPDB1"));
    assertEquals("jdbc:sqlserver://db;user=sa;password=***;encrypt=false",
        clean.apply("jdbc:sqlserver://db;user=sa;password={abc;def}};x};encrypt=false"));
    assertEquals("jdbc:sqlserver://db;user=sa;pwd=***",
        clean.apply("jdbc:sqlserver://db;user=sa;pwd=secret"));
    assertEquals("jdbc:mysql://db/shop?user=root&password1=***&password2=***",
        clean.apply("jdbc:mysql://db/shop?user=root&password1=one&password2=two"));
    // An @ in the password, with a port before it, used to end a user:password@ match early.
    assertEquals("jdbc:sqlserver://db:1433;user=sa;password=***",
        clean.apply("jdbc:sqlserver://db:1433;user=sa;password={a@b;c}"));
    assertEquals("jdbc:postgresql://host:5432/db?user=a&password=***",
        clean.apply("jdbc:postgresql://host:5432/db?user=a&password=p@q"));
    assertEquals("jdbc:mysql://root:***@db/shop", clean.apply("jdbc:mysql://root:pa;ss@db/shop"));
    assertEquals("jdbc:oracle:thin:scott/***@db:1521/svc",
        clean.apply("jdbc:oracle:thin:scott/\"p@ss\"@db:1521/svc"));
    // A malformed braced value is hidden whole, and a very long one doesn't overflow the stack.
    assertEquals("jdbc:sqlserver://db;password=***",
        clean.apply("jdbc:sqlserver://db;password={a}}"));
    assertEquals("jdbc:sqlserver://db;password=***",
        clean.apply("jdbc:sqlserver://db;password={" + "x".repeat(200_000) + "}"));
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

  @Test
  void readsThePasswordFromAMountedSecretFile() throws Exception {
    Path secret = Files.writeString(resources.resolve("db-password"), "s3cr3t\n");
    var fromFile = ProjectDatabaseConfig.load(resources, Map.of("password-file", secret.toString()));
    assertEquals("s3cr3t", fromFile.password(), "the trailing line break is not part of it");

    var direct = ProjectDatabaseConfig.load(resources,
        Map.of("password", "given", "password-file", secret.toString()));
    assertEquals("given", direct.password(), "a password given directly wins");

    var missing = assertThrows(IllegalStateException.class, () -> ProjectDatabaseConfig.load(
        resources, Map.of("password-file", resources.resolve("absent").toString())));
    org.junit.jupiter.api.Assertions.assertTrue(
        missing.getMessage().contains("--password-file"), missing.getMessage());
  }
}
