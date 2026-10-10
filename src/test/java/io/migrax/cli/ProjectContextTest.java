package io.migrax.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectContextTest {
  @TempDir
  Path directory;

  @Test
  void findsProjectRootFromNestedWorkingDirectory() throws Exception {
    Path root = directory.resolve("project");
    Path nested = root.resolve("src/main/java");
    Files.createDirectories(nested);
    Files.writeString(root.resolve("pom.xml"), "<project/>");

    assertEquals(root, ProjectContext.findRoot(nested));
  }

  @Test
  void usesMavenGroupIdWhenEntityPackageIsNotSpecified() throws Exception {
    Path root = directory.resolve("project");
    Files.createDirectories(root);
    Files.writeString(root.resolve("pom.xml"), """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <parent><groupId>io.example</groupId></parent>
          <artifactId>orders</artifactId>
        </project>
        """);

    assertEquals("io.example", ProjectContext.packageName(root, null));
    assertEquals("io.custom", ProjectContext.packageName(root, "io.custom"));
  }

  @Test
  void addsExplicitRuntimeClasspathEntries() throws Exception {
    Path applicationClasses = directory.resolve("application-classes");
    Files.createDirectories(applicationClasses.resolve("sample"));
    Files.writeString(applicationClasses.resolve("sample/runtime-marker.txt"), "present");

    try (var loader = ProjectContext.runtimeLoader(
        directory, new String[]{"migrate", "--classpath", applicationClasses.toString()})) {
      assertTrue(loader.getResource("sample/runtime-marker.txt") != null);
    }
  }

  @Test
  void usesGradleGroupOrConfiguredPackage() throws Exception {
    Path gradle = Files.createDirectories(directory.resolve("gradle-project"));
    Files.writeString(gradle.resolve("build.gradle.kts"),
        "plugins { java }\n\ngroup = \"com.example.shop\"\nversion = \"1.0\"\n");
    org.junit.jupiter.api.Assertions.assertEquals("com.example.shop",
        ProjectContext.packageName(gradle, null));

    Files.createDirectories(gradle.resolve("src/main/resources"));
    Files.writeString(gradle.resolve("src/main/resources/application.properties"),
        "migrax.package=com.example.domain\n");
    org.junit.jupiter.api.Assertions.assertEquals("com.example.domain",
        ProjectContext.packageName(gradle, null));
  }

  @Test
  void addsTheJarsOfTheDriversFolderAfterTheProjectsOwn(@TempDir Path drivers) throws Exception {
    Files.writeString(drivers.resolve("postgresql.jar"), "");
    Files.writeString(drivers.resolve("readme.txt"), "");
    System.setProperty("migrax.drivers", drivers.toString());
    try {
      assertEquals(java.util.List.of(drivers.resolve("postgresql.jar").toAbsolutePath()),
          ProjectContext.driverJars());
      Path own = Files.createFile(drivers.resolve("own-driver.jar.copy"));
      try (var loader = ProjectContext.runtimeLoader(drivers, own.toString(), java.util.List.of())) {
        var urls = java.util.Arrays.asList(loader.getURLs());
        assertEquals(own.toUri().toURL(), urls.get(0), "the project's classpath comes first");
        assertEquals(drivers.resolve("postgresql.jar").toUri().toURL(), urls.get(urls.size() - 1));
      }
    } finally {
      System.clearProperty("migrax.drivers");
    }
  }
}
