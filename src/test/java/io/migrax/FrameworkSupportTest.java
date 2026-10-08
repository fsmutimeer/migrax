package io.migrax;

import io.migrax.model.NamingStrategy;
import io.migrax.plugin.ProjectDatabaseConfig;
import io.migrax.plugin.ProjectDatabaseConfig.Framework;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Micronaut and Helidon configuration, and naming per framework. */
class FrameworkSupportTest {
  @TempDir
  Path project;

  private Path resources() throws Exception {
    return Files.createDirectories(project.resolve("src/main/resources"));
  }

  private void pom(String dependency) throws Exception {
    Files.writeString(project.resolve("pom.xml"),
        "<project><dependencies><dependency>" + dependency + "</dependency></dependencies>"
            + "</project>");
  }

  @Test
  void micronautSnakeCaseMatchesMicronautData() {
    // Expected values come from Micronaut 4.10 NameUtils.underscoreSeparate(..).toLowerCase().
    Map<String, String> expected = Map.ofEntries(
        Map.entry("fullName", "full_name"), Map.entry("myURL", "my_url"),
        Map.entry("URLValue", "urlvalue"), Map.entry("addressLine1", "address_line1"),
        Map.entry("line1Address", "line1_address"), Map.entry("product_id", "product_id"),
        Map.entry("OrderItem", "order_item"), Map.entry("ID", "id"),
        Map.entry("isActive", "is_active"), Map.entry("x2y", "x2y"),
        Map.entry("productSKU", "product_sku"), Map.entry("HTMLParser", "htmlparser"));
    expected.forEach((name, snake) ->
        assertEquals(snake, NamingStrategy.micronautSnakeCase(name), name));
    // Hibernate's rule differs on these, which is why Micronaut has its own mode.
    assertEquals("myurl", NamingStrategy.SPRING.physical("myURL"));
    assertEquals("my_url", NamingStrategy.MICRONAUT.physical("myURL"));
  }

  @Test
  void readsMicronautDataSourceAndPicksMicronautNaming() throws Exception {
    pom("<groupId>io.micronaut.data</groupId><artifactId>micronaut-data-hibernate-jpa</artifactId>");
    Files.writeString(resources().resolve("application.yml"), """
        micronaut:
          application:
            name: shop
        datasources:
          default:
            url: jdbc:postgresql://localhost:5432/shop
            username: app
            password: secret
        jpa:
          default:
            properties:
              hibernate:
                hbm2ddl:
                  auto: update
        """);

    var credentials = ProjectDatabaseConfig.load(resources(), Map.of());
    assertEquals("jdbc:postgresql://localhost:5432/shop", credentials.url());
    assertEquals("app", credentials.user());
    assertEquals("secret", credentials.password());
    assertEquals(NamingStrategy.MICRONAUT,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null));
    Map<String, String> config = ProjectDatabaseConfig.settings(resources());
    assertEquals(Framework.MICRONAUT_DATA, ProjectDatabaseConfig.detectFramework(config, project));
    assertEquals(java.util.List.of("jpa.default.properties.hibernate.hbm2ddl.auto=update"),
        ProjectDatabaseConfig.schemaGeneration(config));
  }

  @Test
  void micronautWithoutMicronautDataKeepsHibernateNaming() throws Exception {
    pom("<groupId>io.micronaut.sql</groupId><artifactId>micronaut-hibernate-jpa</artifactId>");
    Files.writeString(resources().resolve("application.properties"),
        "datasources.main.url=jdbc:h2:mem:main\n");
    assertEquals("jdbc:h2:mem:main", ProjectDatabaseConfig.load(resources(), Map.of()).url());
    assertEquals(NamingStrategy.JPA,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null));
  }

  @Test
  void readsHelidonMicroProfileDataSourceNamedByPersistenceXml() throws Exception {
    pom("<groupId>io.helidon.integrations.cdi</groupId><artifactId>helidon-integrations-cdi-jpa</artifactId>");
    Path metaInf = Files.createDirectories(resources().resolve("META-INF"));
    Files.writeString(metaInf.resolve("microprofile-config.properties"), """
        javax.sql.DataSource.reports.dataSourceClassName=org.h2.jdbcx.JdbcDataSource
        javax.sql.DataSource.reports.dataSource.url=jdbc:h2:mem:reports
        javax.sql.DataSource.shop.dataSourceClassName=com.mysql.cj.jdbc.MysqlDataSource
        javax.sql.DataSource.shop.dataSource.url=jdbc:mysql://localhost:3306/shop
        javax.sql.DataSource.shop.dataSource.user=app
        javax.sql.DataSource.shop.dataSource.password=secret
        """);
    Files.writeString(metaInf.resolve("persistence.xml"), """
        <?xml version="1.0" encoding="UTF-8"?>
        <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
          <persistence-unit name="shop" transaction-type="JTA">
            <jta-data-source>shop</jta-data-source>
            <properties>
              <property name="hibernate.hbm2ddl.auto" value="create"/>
            </properties>
          </persistence-unit>
        </persistence>
        """);

    var credentials = ProjectDatabaseConfig.load(resources(), Map.of());
    assertEquals("jdbc:mysql://localhost:3306/shop", credentials.url());
    assertEquals("app", credentials.user());
    assertEquals("secret", credentials.password());
    assertEquals(NamingStrategy.JPA,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null));
    assertEquals(java.util.List.of("hibernate.hbm2ddl.auto=create"),
        ProjectDatabaseConfig.schemaGeneration(ProjectDatabaseConfig.settings(resources())));
  }

  @Test
  void readsHelidonSeDbClientConfig() throws Exception {
    pom("<groupId>io.helidon.dbclient</groupId><artifactId>helidon-dbclient-jdbc</artifactId>");
    Files.writeString(resources().resolve("application.yaml"), """
        db:
          source: jdbc
          connection:
            url: jdbc:postgresql://localhost:5432/se
            username: se
            password: pw
        """);
    var credentials = ProjectDatabaseConfig.load(resources(), Map.of());
    assertEquals("jdbc:postgresql://localhost:5432/se", credentials.url());
    assertEquals("se", credentials.user());
  }

  @Test
  void namingRecordedInTheSnapshotWinsOverDetection() throws Exception {
    pom("<groupId>io.helidon</groupId>");
    resources();
    assertEquals(NamingStrategy.JPA,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null, null));
    assertEquals(NamingStrategy.SPRING,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null, "spring"));
    // An explicit setting still wins over the snapshot.
    assertEquals(NamingStrategy.MICRONAUT,
        ProjectDatabaseConfig.namingStrategy(resources(), project, "micronaut", "spring"));
  }

  @Test
  void snapshotsWithoutRecordedNamingKeepTheOldDefaults() throws Exception {
    // Earlier versions used spring naming for every non-Quarkus project and did not record it.
    pom("<groupId>io.micronaut.data</groupId><artifactId>micronaut-data-hibernate-jpa</artifactId>");
    resources();
    assertEquals(NamingStrategy.SPRING,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null, null, true));
    assertEquals(NamingStrategy.MICRONAUT,
        ProjectDatabaseConfig.namingStrategy(resources(), project, null, null, false));
  }

  @Test
  void runningInsideAnotherJvmLeavesItsLoggingAlone() {
    // Main.run also runs inside Maven and Spring Boot applications (the starter).
    String provider = System.clearProperty("org.jboss.logging.provider");
    java.util.logging.Logger hibernate = java.util.logging.Logger.getLogger("org.hibernate");
    java.util.logging.Level level = hibernate.getLevel();
    try {
      io.migrax.cli.Main.run(new String[] {"version"}, new java.io.PrintStream(
          java.io.OutputStream.nullOutputStream()), new java.io.PrintStream(
          java.io.OutputStream.nullOutputStream()));
      assertEquals(null, System.getProperty("org.jboss.logging.provider"));
      assertEquals(level, hibernate.getLevel());
    } finally {
      if (provider != null) {
        System.setProperty("org.jboss.logging.provider", provider);
      }
    }
  }

  @Test
  void findsWhereSchemaGenerationIsSet() throws Exception {
    Path resources = resources();
    Files.writeString(resources.resolve("application.yml"), """
        jpa:
          default:
            properties:
              hibernate:
                hbm2ddl:
                  auto: update
        """);
    Files.writeString(resources.resolve("application.properties"),
        "# dev only\n%dev.quarkus.hibernate-orm.schema-management.strategy=drop-and-create\n");
    Path metaInf = Files.createDirectories(resources.resolve("META-INF"));
    Files.writeString(metaInf.resolve("persistence.xml"), """
        <persistence xmlns="https://jakarta.ee/xml/ns/persistence" version="3.0">
          <persistence-unit name="u">
            <properties>
              <property name="jakarta.persistence.schema-generation.database.action" value="create"/>
            </properties>
          </persistence-unit>
        </persistence>
        """);

    var found = ProjectDatabaseConfig.schemaGenerationSettings(resources,
        ProjectDatabaseConfig.settings(resources));
    var byKey = new java.util.HashMap<String, ProjectDatabaseConfig.SchemaGeneration>();
    found.forEach(f -> byKey.put(f.key(), f));

    var yaml = byKey.get("jpa.default.properties.hibernate.hbm2ddl.auto");
    assertEquals("application.yml", yaml.file());
    assertEquals(6, yaml.line());
    assertEquals(false, yaml.deletesData());
    assertEquals(true, yaml.acceptsValidate());

    var dev = byKey.get("%dev.quarkus.hibernate-orm.schema-management.strategy");
    assertEquals("application.properties", dev.file());
    assertEquals(2, dev.line());
    assertEquals(true, dev.deletesData());

    var xml = byKey.get("jakarta.persistence.schema-generation.database.action");
    assertEquals("META-INF/persistence.xml", xml.file());
    assertEquals(4, xml.line());
    // The JPA-standard action has no "validate" value.
    assertEquals(false, xml.acceptsValidate());
  }
}
