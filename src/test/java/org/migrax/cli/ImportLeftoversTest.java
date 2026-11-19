package org.migrax.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
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
  void neverShowsAValueThatHasTheToolsNameInIt() throws Exception {
    write("build.gradle", """
        plugins { id 'org.flywaydb.flyway' version '11.0' }
        password = 'flywaySECRET2'
        def pw = System.getenv('PW') ?: 'flyway-SECRET4'
        url = "jdbc:postgresql://admin:flyway-Pw9@db/app"
        implementation libs.flyway.core
        """);
    write("gradle/libs.versions.toml", """
        token = "flyway-SECRET12"
        db = "jdbc:postgresql://admin:flyway-Pw9@db/app"
        flyway-core = { module = "org.flywaydb:flyway-core", version = "11.0" }
        """);
    write("src/main/resources/application.properties", """
        app.secret=abc\\
          flyway.SECRET6
        # spring.flyway.url=x\\
        spring.flyway.enabled=false
        """);
    List<String> found = find("flyway");
    assertEquals(List.of(
        "build.gradle:1: org.flywaydb.flyway: remove it",
        "build.gradle:5: libs.flyway.core: remove it",
        "gradle/libs.versions.toml:3: flyway-core: remove it",
        "src/main/resources/application.properties:4: spring.flyway.enabled: remove it"), found);
    assertTrue(found.stream().noneMatch(line -> line.contains("SECRET")
        || line.contains("Pw9")), found.toString());
  }

  @Test
  void aBrokenPomIsScannedQuickly() throws Exception {
    write("pom.xml", "<project>" + "<!-- <dependency>".repeat(40_000)
        + "<dependency>".repeat(20_000) + "</project>");
    assertTimeoutPreemptively(Duration.ofSeconds(5),
        () -> assertEquals(List.of(), find("flyway")));
  }

  @Test
  void findsGradleCoordinatesWithAnyVersionButShowsNoVersion() throws Exception {
    write("build.gradle", String.join("\n",
        "implementation \"org.flywaydb:flyway-core:$flywayVersion\"",
        "implementation \"org.flywaydb:flyway-database-postgresql:${flywayVersion}\"",
        "implementation 'org.liquibase:liquibase-core:4.+'",
        "runtimeOnly 'com.acme:acme-flyway:1.0@jar'",
        "url = 'jdbc:flyway-x:secret'"));
    List<String> found = find("flyway");
    assertEquals(List.of(
        "build.gradle:1: org.flywaydb:flyway-core: remove it",
        "build.gradle:2: org.flywaydb:flyway-database-postgresql: remove it",
        "build.gradle:4: com.acme:acme-flyway: remove it"), found);
    assertEquals(List.of("build.gradle:3: org.liquibase:liquibase-core: remove it"),
        find("liquibase"));
  }

  @Test
  void neverShowsValuesInLongStringsOrCoordinatesOfOtherGroups() throws Exception {
    write("build.gradle", String.join("\n",
        "def a = /flywaySECRET1/",
        "def b = $/flywaySECRET2 with / inside/$",
        "def c = '''",
        "flywaySECRET3 {",
        "'''",
        "def d = \"\"\"flywaySECRET4\"\"\"",
        "def e = 'db.prod:flyway-hunter2secret'",
        "ext.pairs = [\"k\": \"db.prod:flyway-hunter3secret\"]",
        "def half = total / flywayCount",
        "implementation 'com.acme:acme-flyway:1.0'",
        "password 'acme.secret:flyway-pa55word'",
        "def own = 'org.flywaydb:flyway-pa66word'"));
    write("app/build.gradle.kts", String.join("\n",
        "val token = \"\"\"",
        "  flywaySECRET5",
        "\"\"\"",
        "val m = mapOf(\"pw\" to \"db.prod:flyway-hunter4secret\")",
        "implementation(\"com.acme:acme-flyway:1.0\")",
        "testImplementation(platform(\"com.acme:acme-flyway-bom:1.0\"))",
        "liquibaseRuntime \"org.liquibase:liquibase-core:4.0\""));
    write("gradle/libs.versions.toml", String.join("\n",
        "[libraries]",
        "qf = { module = \"io.quarkus:quarkus-flyway\" }",
        "noreturn = \"db.prod:flyway-hunter5secret\""));
    List<String> found = find("flyway");
    assertEquals(List.of(
        "app/build.gradle.kts:4: flyway coordinate: remove it",
        "app/build.gradle.kts:5: com.acme:acme-flyway: remove it",
        "app/build.gradle.kts:6: com.acme:acme-flyway-bom: remove it",
        "build.gradle:7: flyway coordinate: remove it",
        "build.gradle:8: flyway coordinate: remove it",
        "build.gradle:9: flywayCount: remove it",
        "build.gradle:10: com.acme:acme-flyway: remove it",
        "build.gradle:11: flyway coordinate: remove it",
        "build.gradle:12: flyway coordinate: remove it",
        "gradle/libs.versions.toml:2: io.quarkus:quarkus-flyway: remove it",
        "gradle/libs.versions.toml:3: flyway coordinate: remove it"), found);
    assertTrue(found.stream().noneMatch(line -> line.contains("SECRET")
        || line.contains("hunter") || line.contains("word")), found.toString());
    assertTrue(find("liquibase").contains("app/build.gradle.kts:7: liquibaseRuntime: remove it"),
        find("liquibase").toString());
  }

  @Test
  void neverShowsAValueThatOnlyStartsLikeTheToolsGroup() throws Exception {
    write("build.gradle", String.join("\n",
        "token = 'org.flywaydb:Hunter2Secret'",
        "other = 'org.flywaydbSECRET'",
        "third = 'org.flywaydb:hunter2secret'",
        "fourth = 'org.flywaydb.hunter2secret'",
        "plugins { id 'org.flywaydb.flyway' version '11.0' }"));
    assertEquals(List.of("build.gradle:5: org.flywaydb.flyway: remove it"), find("flyway"));
  }

  @Test
  void showsControlCharactersAsQuestionMarks() throws Exception {
    write("src/main/resources/application.properties", "spring.flyway.\u001b[2Jx=1\n");
    assertEquals(List.of("src/main/resources/application.properties:1: "
        + "spring.flyway.?[2Jx: remove it"), find("flyway"));
  }

  @Test
  void aLongLineFullOfQuotesIsScannedQuickly() throws Exception {
    write("build.gradle", "a\"\"".repeat(600_000) + "\nimplementation 'org.flywaydb:flyway-core'");
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(
        List.of("build.gradle:2: org.flywaydb:flyway-core: remove it"), find("flyway")));
  }

  @Test
  void skipsLinksOutOfTheProjectAndHugeFiles(@TempDir Path elsewhere) throws Exception {
    Files.writeString(elsewhere.resolve("pom.xml"),
        "<project><dependency><groupId>org.flywaydb</groupId>"
            + "<artifactId>flyway-core</artifactId></dependency></project>");
    Files.createDirectories(project.resolve("modules"));
    folderLink(project.resolve("modules/linked"), elsewhere);
    write("big/pom.xml", "<project><dependency><groupId>org.flywaydb</groupId>"
        + "<artifactId>flyway-core</artifactId></dependency>" + " ".repeat(2_100_000)
        + "</project>");
    assertEquals(List.of(), find("flyway"));
  }

  private static void folderLink(Path link, Path target) throws Exception {
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(),
          target.toString()).redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      assertEquals(0, process.waitFor());
    } else {
      Files.createSymbolicLink(link, target);
    }
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
