package io.migrax.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises Maven classpath resolution and caching with a fake {@code mvnw} wrapper. */
class ProjectBuildTest {
  @TempDir
  Path project;

  private Path dependency;
  private Path invocations;

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(project.resolve("pom.xml"), "<project><groupId>x</groupId></project>");
    dependency = Files.createFile(project.resolve("dependency.jar"));
    invocations = project.resolve("invocations.log");

    // A wrapper that records its arguments and writes the classpath file Maven would write.
    Files.writeString(project.resolve("FakeMaven.java"), """
        import java.nio.file.*;
        public class FakeMaven {
          public static void main(String[] args) throws Exception {
            Path root = Path.of(System.getProperty("user.dir"));
            Files.writeString(root.resolve("invocations.log"), String.join(" ", args) + "\\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (Files.exists(root.resolve("fail"))) {
              System.out.println("[ERROR] simulated build failure");
              System.exit(1);
            }
            for (String arg : args) {
              if (arg.startsWith("-Dmdep.outputFile=")) {
                Files.writeString(Path.of(arg.substring("-Dmdep.outputFile=".length())),
                    root.resolve("dependency.jar").toString());
              }
            }
          }
        }
        """);
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
      Files.writeString(project.resolve("mvnw.cmd"),
          "@\"" + java + "\" \"%~dp0FakeMaven.java\" %*\r\n");
    } else {
      Path wrapper = project.resolve("mvnw");
      Files.writeString(wrapper, "#!/bin/sh\nexec '" + java
          + "' \"$(dirname \"$0\")/FakeMaven.java\" \"$@\"\n");
      wrapper.toFile().setExecutable(true);
    }
  }

  private ProjectBuild.Result prepare(boolean compile, boolean refresh) throws IOException {
    return new ProjectBuild(project, new PrintStream(new ByteArrayOutputStream()))
        .prepare(new ProjectBuild.Request(compile, refresh));
  }

  private List<String> calls() throws IOException {
    return Files.exists(invocations) ? Files.readAllLines(invocations) : List.of();
  }

  @Test
  void resolvesOnceAndReusesTheCacheUntilThePomChanges() throws Exception {
    ProjectBuild.Result first = prepare(false, false);
    assertEquals(ProjectBuild.Tool.MAVEN, first.tool());
    assertEquals(List.of(dependency), first.dependencies());
    assertEquals(1, calls().size());
    assertTrue(calls().get(0).contains("dependency:build-classpath"), calls().get(0));
    assertTrue(Files.isRegularFile(project.resolve(".migrax/.gitignore")));

    prepare(false, false);
    assertEquals(1, calls().size(), "cached classpath should be reused");

    Files.writeString(project.resolve("pom.xml"), "<project><groupId>y</groupId></project>");
    prepare(false, false);
    assertEquals(2, calls().size(), "a changed pom.xml should trigger resolution");

    prepare(false, true);
    assertEquals(3, calls().size(), "--refresh should force resolution");
  }

  @Test
  void compilesWhenSourcesChange() throws Exception {
    Path source = project.resolve("src/main/java/demo/Item.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo; class Item {}");
    Files.createDirectories(project.resolve("target/classes"));

    prepare(true, false);
    assertEquals(1, calls().size());
    assertTrue(calls().get(0).contains("compile"), calls().get(0));

    prepare(true, false);
    assertEquals(1, calls().size(), "unchanged sources should not recompile");

    Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 5_000));
    prepare(true, false);
    assertEquals(2, calls().size(), "edited sources should recompile");
    assertTrue(calls().get(1).contains("compile"), calls().get(1));
  }

  @Test
  void reportsBuildFailuresWithTheToolOutput() throws Exception {
    Files.createFile(project.resolve("fail"));
    IOException error = assertThrows(IOException.class, () -> prepare(false, false));
    assertTrue(error.getMessage().contains("Maven build failed"), error.getMessage());
    assertTrue(error.getMessage().contains("simulated build failure"), error.getMessage());
  }

  @Test
  void projectsWithoutABuildFileSkipResolution() throws Exception {
    Files.delete(project.resolve("pom.xml"));
    ProjectBuild.Result result = prepare(true, false);
    assertEquals(ProjectBuild.Tool.NONE, result.tool());
    assertTrue(result.dependencies().isEmpty());
    assertEquals(0, calls().size());
  }
}
