package io.migrax.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
  @TempDir
  Path project;

  @Test
  void generatesAndAppliesMigrationFromProjectConfiguration() throws Exception {
    Files.writeString(project.resolve("pom.xml"), """
        <project>
          <groupId>io.migrax.cli.fixture</groupId>
          <artifactId>sample</artifactId>
        </project>
        """);
    String url = "jdbc:h2:mem:migrax_cli_test;DB_CLOSE_DELAY=-1";
    Path resources = project.resolve("src/main/resources");
    Files.createDirectories(resources);
    Files.writeString(resources.resolve("application.properties"), """
        spring.datasource.url=%s
        migrax.locations=filesystem:sql/migrations
        """.formatted(url));

    Main.main(new String[]{
        "generate",
        "--dir", project.toString()});

    Path migrationDirectory = project.resolve("sql/migrations");
    try (var files = Files.list(migrationDirectory)) {
      assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".sql")));
    }

    Main.main(new String[]{"migrate", "--dir", project.toString()});

    try (var connection = DriverManager.getConnection(url);
         var statement = connection.createStatement();
         var result = statement.executeQuery("select count(*) from \"sample_entity\"")) {
      assertTrue(result.next());
    }
  }

}
