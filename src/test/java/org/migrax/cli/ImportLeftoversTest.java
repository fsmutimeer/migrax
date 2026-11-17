package org.migrax.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ImportLeftoversTest {
  @TempDir
  Path project;

  private List<String> find(String tool) throws Exception {
    return ImportLeftovers.find(project, tool).stream().map(Object::toString).toList();
  }

  private void write(String file, String content) throws Exception {
    Path path = project.resolve(file);
    Files.createDirectories(path.getParent());
    Files.writeString(path, content);
  }

  @Test
  void findsMavenDependenciesAndPlugins() throws Exception {
    write("pom.xml", """
        <project>
          <groupId>com.acme</groupId>
          <artifactId>flyway-demo</artifactId>
          <properties><flyway.version>11.0</flyway.version></properties>
          <dependencies>
            <dependency>
              <groupId>org.flywaydb</groupId>
              <artifactId>flyway-core</artifactId>
            </dependency>
            <!-- <dependency><artifactId>flyway-mysql</artifactId></dependency>
            <dependency><artifactId>flyway-sqlserver</artifactId></dependency> -->
            <dependency>
              <groupId>com.acme</groupId>
              <artifactId>flyway-demo-api</artifactId>
            </dependency>
            <dependency>
              <groupId>org.example</groupId>
              <artifactId>legacy-tools</artifactId>
              <exclusions><exclusion>
                <artifactId>flyway-core</artifactId>
              </exclusion></exclusions>
            </dependency>
          </dependencies>
          <build><plugins><plugin>
            <artifactId>flyway-maven-plugin</artifactId>
            <configuration><password>secret-value</password></configuration>
          </plugin></plugins></build>
        </project>
        """);
    assertEquals(List.of("pom.xml:8: dependency flyway-core: remove it",
        "pom.xml:25: plugin flyway-maven-plugin: remove it"), find("flyway"));
    assertEquals(List.of(), find("liquibase"));
  }

  @Test
  void findsGradleNamesWithoutPrintingTheRestOfTheLine() throws Exception {
    write("app/build.gradle.kts", """
        plugins { id("org.liquibase.gradle") version "2.2.0" }
        // liquibase was here
        /* liquibase {
           changeLogFile = "x" } */
        group = "com.acme.liquibase"
        dependencies {
          liquibaseRuntime("org.liquibase:liquibase-core")
          implementation(project(":liquibase-demo-api"))
        }
        liquibase {
          activities.register("main") { arguments = mapOf("password" to "secret-value") }
        }
        liquibase.password = "secret-value"
        """);
    write("gradle/libs.versions.toml", """
        # liquibase
        liquibase = "4.31.0"
        liquibase-core = { module = "org.liquibase:liquibase-core", version.ref = "liquibase" }
        """);
    List<String> found = find("liquibase");
    assertEquals(List.of(
        "app/build.gradle.kts:1: org.liquibase.gradle: remove it",
        "app/build.gradle.kts:7: liquibaseRuntime: remove it",
        "app/build.gradle.kts:10: liquibase: remove it",
        "gradle/libs.versions.toml:3: liquibase-core: remove it"), found);
    assertTrue(found.stream().noneMatch(line -> line.contains("secret-value")));
  }

  @Test
  void namesTheMigraxSettingThatReplacesEachKeyWithoutItsValue() throws Exception {
    write("src/main/resources/application.properties", """
        spring.datasource.url=jdbc:h2:mem:x
        # spring.flyway.enabled=false
        spring.flyway.url=jdbc:h2:mem:other
        spring.flyway.password=secret-value
        spring.flyway.placeholders.Owner=app
        %prod.quarkus.flyway.migrate-at-start=true
        flyway.datasources.default.locations=classpath:db/migration
        spring.flyway.user\t= sa
        quarkus.flyway."billing".locations=db/billing
        flyway.datasources.billing.placeholders.owner=app
        spring.flywayish=true
        """);
    List<String> found = find("flyway");
    String file = "src/main/resources/application.properties:";
    assertEquals(List.of(
        file + "3: spring.flyway.url: use migrax.url instead",
        file + "4: spring.flyway.password: use migrax.password instead",
        file + "5: spring.flyway.placeholders.Owner: use migrax.placeholders.Owner instead",
        file + "6: %prod.quarkus.flyway.migrate-at-start: remove it",
        file + "7: flyway.datasources.default.locations: use migrax.locations instead",
        file + "8: spring.flyway.user: use migrax.user instead",
        file + "9: quarkus.flyway.\"billing\".locations: "
            + "use migrax.databases.billing.locations instead",
        file + "10: flyway.datasources.billing.placeholders.owner: "
            + "use migrax.databases.billing.placeholders.owner instead"), found);
    assertTrue(found.stream().noneMatch(line -> line.contains("secret-value")));
  }

  @Test
  void readsNestedYamlKeysListsAndFlowValues() throws Exception {
    write("src/main/resources/application-prod.yml", """
        spring:
          datasource:
            url: jdbc:h2:mem:x
          liquibase:
            # comment
            change-log: classpath:db/changelog.xml
            default-schema: app
            user: "admin"
        description: |
          liquibase:
            enabled: true
        ---
        liquibase.enabled: false
        ---
        spring:
          liquibase: {enabled: false}
        quarkus:
          liquibase:
            change-log-parameters:
              - a
        """);
    String file = "src/main/resources/application-prod.yml:";
    assertEquals(List.of(
        file + "6: spring.liquibase.change-log: remove it",
        file + "7: spring.liquibase.default-schema: use migrax.schemas instead",
        file + "8: spring.liquibase.user: use migrax.user instead",
        file + "13: liquibase.enabled: remove it",
        file + "16: spring.liquibase: remove it",
        file + "19: quarkus.liquibase.change-log-parameters: remove it"),
        find("liquibase"));
  }

  @Test
  void skipsBuildOutputAndHiddenFoldersButNotModulesNamedLikeThem() throws Exception {
    write("target/classes/application.properties", "spring.flyway.enabled=true\n");
    write(".idea/pom.xml", "<dependency><artifactId>flyway-core</artifactId></dependency>\n");
    write("notes/application.txt", "spring.flyway.enabled=true\n");
    assertEquals(List.of(), find("flyway"));
    write("build/pom.xml", "<project><dependencies><dependency>"
        + "<artifactId>flyway-core</artifactId></dependency></dependencies></project>\n");
    assertEquals(List.of("build/pom.xml:1: dependency flyway-core: remove it"), find("flyway"));
  }
}
