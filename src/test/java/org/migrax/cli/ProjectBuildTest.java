package org.migrax.cli;

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

  /** Compiles the sources into target/classes, as a real Maven build would. */
  private void compileLikeMaven(Path... sources) {
    List<String> arguments = new java.util.ArrayList<>(List.of(
        "-d", project.resolve("target/classes").toString()));
    for (Path source : sources) {
      arguments.add(source.toString());
    }
    assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler()
        .run(null, null, new ByteArrayOutputStream(), arguments.toArray(String[]::new)));
  }

  private Path source(String name, String text, long time) throws IOException {
    // A real (empty) jar: the compiler rejects the zero-byte placeholder.
    if (Files.size(dependency) == 0) {
      try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(dependency))) {
        jar.putNextEntry(new java.util.jar.JarEntry("META-INF/"));
      }
    }
    Path file = project.resolve("src/main/java/demo/" + name + ".java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
    Files.setLastModifiedTime(file, FileTime.fromMillis(time));
    return file;
  }

  @Test
  void compilesEditedJavaFilesWithoutMaven() throws Exception {
    long time = System.currentTimeMillis() - 60_000;
    Path item = source("Item", "package demo; public class Item { public static class Old {} }",
        time);
    Path other = source("Other", "package demo; public class Other { Item item; }", time);
    compileLikeMaven(item, other);
    Files.writeString(project.resolve("target/classes/app.properties"), "kept=yes");
    prepare(true, false);
    assertEquals(1, calls().size());

    source("Item", "package demo; public class Item { public String added; }", time + 5_000);
    ProjectBuild.Result quick = prepare(true, false);
    assertEquals(1, calls().size(), "an edited Java file should not run Maven");
    Path classes = project.resolve(".migrax/classes");
    assertEquals(classes, quick.classes());
    assertEquals(project.resolve("target/classes"), quick.replaces());
    assertTrue(Files.isRegularFile(classes.resolve("demo/Other.class")));
    assertTrue(Files.isRegularFile(classes.resolve("app.properties")));
    assertTrue(Files.notExists(classes.resolve("demo/Item$Old.class")),
        "classes removed from the edited file should go");
    assertTrue(Files.exists(project.resolve("target/classes/demo/Item$Old.class")),
        "the build tool's own folder should not change");
    try (var loader = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
        null)) {
      loader.loadClass("demo.Item").getField("added");
    }

    assertEquals(classes, prepare(true, false).classes(), "the copy is reused while current");
    assertEquals(1, calls().size());
    assertTrue(Files.readString(project.resolve(".migrax/.gitignore")).contains("classes/"));
  }

  @Test
  void fallsBackToMavenWhenTheQuickPathIsNotSafe() throws Exception {
    long time = System.currentTimeMillis() - 60_000;
    Path item = source("Item", "package demo; public class Item {}", time);
    Path other = source("Other", "package demo; public class Other {}", time);
    compileLikeMaven(item, other);
    prepare(true, false);

    // A compile error: Maven runs and reports it.
    source("Item", "package demo; public class Item { broken }", time + 5_000);
    ProjectBuild.Result result = prepare(true, false);
    assertEquals(2, calls().size(), "a compile error should run Maven");
    assertEquals(null, result.classes());

    // The class folder changed since the last build (here: a failed build emptied it).
    source("Item", "package demo; public class Item { int fixed; }", time + 10_000);
    Files.delete(project.resolve("target/classes/demo/Other.class"));
    prepare(true, false);
    assertEquals(3, calls().size(), "a changed class folder should run Maven");

    // A deleted source file.
    Files.delete(other);
    prepare(true, false);
    assertEquals(4, calls().size(), "a deleted source should run Maven");
  }

  /** A built project (Item, Other) whose next prepare would take the quick path. */
  private long builtProject() throws Exception {
    long time = System.currentTimeMillis() - 60_000;
    Path item = source("Item", "package demo; public class Item {}", time);
    Path other = source("Other", "package demo; public class Other {}", time);
    compileLikeMaven(item, other);
    prepare(true, false);
    assertEquals(1, calls().size());
    return time;
  }

  @Test
  void anAddedEntityIsCompiledToo() throws Exception {
    long time = builtProject();
    source("Added", "package demo; public class Added { Item item; }", time + 5_000);
    ProjectBuild.Result quick = prepare(true, false);
    assertEquals(1, calls().size(), "an added Java file should not run Maven");
    assertTrue(Files.isRegularFile(quick.classes().resolve("demo/Added.class")));
  }

  @Test
  void constantsUsedByUneditedClassesAreCurrent() throws Exception {
    long time = System.currentTimeMillis() - 60_000;
    Path names = source("Names", "package demo; public class Names {"
        + " public static final String TABLE = \"old\"; }", time);
    Path user = source("User", "package demo; public class User {"
        + " public static final String USED = Names.TABLE; }", time);
    compileLikeMaven(names, user);
    prepare(true, false);
    source("Names", "package demo; public class Names {"
        + " public static final String TABLE = \"new\"; }", time + 5_000);
    Path classes = prepare(true, false).classes();
    try (var loader = new java.net.URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
        null)) {
      assertEquals("new", loader.loadClass("demo.User").getField("USED").get(null));
    }
  }

  @Test
  void aClassWithoutJavaSourceRunsMaven() throws Exception {
    long time = System.currentTimeMillis() - 60_000;
    Path item = source("Item", "package demo; public class Item {}", time);
    Path gone = source("Generated", "package demo; public class Generated {}", time);
    compileLikeMaven(item, gone);
    Files.delete(gone);  // as if a processor or another language had made Generated.class
    prepare(true, false);
    int before = calls().size();
    source("Item", "package demo; public class Item { int edited; }", time + 5_000);
    assertEquals(null, prepare(true, false).classes());
    assertEquals(before + 1, calls().size(), "a class the copy can't make should run Maven");
  }

  @Test
  void kotlinSourcesAndAnnotationProcessorsRunMaven() throws Exception {
    long time = builtProject();
    Path kotlin = project.resolve("src/main/kotlin/demo/Extra.kt");
    Files.createDirectories(kotlin.getParent());
    Files.writeString(kotlin, "package demo");
    prepare(true, false);
    assertEquals(2, calls().size(), "a Kotlin source should run Maven");
    Files.delete(kotlin);
    prepare(true, false);

    int before = calls().size();
    Files.writeString(project.resolve("pom.xml"),
        "<project><dependency><artifactId>lombok</artifactId></dependency></project>");
    prepare(true, false);  // resolves the classpath again for the changed pom
    source("Item", "package demo; public class Item { int edited; }", time + 5_000);
    prepare(true, false);
    assertEquals(before + 2, calls().size(), "Lombok in the pom should run Maven");
  }

  @Test
  void canBeTurnedOff() throws Exception {
    long time = builtProject();
    source("Item", "package demo; public class Item { int edited; }", time + 5_000);
    System.setProperty("migrax.quick-compile", "false");
    try {
      prepare(true, false);
    } finally {
      System.clearProperty("migrax.quick-compile");
    }
    assertEquals(2, calls().size(), "-Dmigrax.quick-compile=false should run Maven");
  }

  @Test
  void addsTheNewCacheFilesToAnOlderGitignore() throws Exception {
    Path ignore = project.resolve(".migrax/.gitignore");
    Files.createDirectories(ignore.getParent());
    Files.writeString(ignore, "# Machine-local Migrax cache files. Keep snapshot.json under"
        + " version control.\nclasspath.txt\n");
    ProjectBuild.ensureStateDirectory(project);
    List<String> lines = Files.readAllLines(ignore);
    assertTrue(lines.contains("classes/") && lines.contains("sources.txt"), lines.toString());
    assertEquals(1, lines.stream().filter("classpath.txt"::equals).count());

    Files.writeString(ignore, "my own rules\n");
    ProjectBuild.ensureStateDirectory(project);
    assertEquals("my own rules\n", Files.readString(ignore), "a hand-written file stays");
  }

  @Test
  void theLoaderUsesTheCopyInPlaceOfTheClassFolder() throws Exception {
    Path target = Files.createDirectories(project.resolve("target/classes"));
    Path copy = Files.createDirectories(project.resolve(".migrax/classes"));
    try (var loader = ProjectContext.runtimeLoader(project, null, List.of(dependency), copy,
        target)) {
      List<java.net.URL> urls = List.of(loader.getURLs());
      assertEquals(List.of(copy.toUri().toURL(), dependency.toUri().toURL()), urls);
    }
    // When the replaced folder isn't among the entries, the copy still comes first.
    Files.delete(target);
    try (var loader = ProjectContext.runtimeLoader(project, null, List.of(dependency), copy,
        target)) {
      assertEquals(copy.toUri().toURL(), loader.getURLs()[0]);
    }
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
