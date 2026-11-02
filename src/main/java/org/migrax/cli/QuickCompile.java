package org.migrax.cli;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.migrax.util.Log;

/**
 * Compiles the project's Java sources with the JDK's own compiler when only Java files were
 * edited since the last Maven or Gradle build, so a command after an entity edit doesn't wait
 * for the build tool.
 *
 * <p>All of {@code src/main/java} is compiled (so constants and other classes that depend on
 * an edited file are current) into {@code .migrax/classes}, together with the resource files
 * of the build tool's class folder. That copy then stands in for the class folder; the
 * project's own build output is never written. The build tool runs instead whenever this could
 * give a different result: no JDK compiler, a deleted or non-Java source, Kotlin sources,
 * annotation processors, a compile error, a class folder that changed since the last build, or
 * a class of the last build that the copy doesn't have (generated or non-Java classes).
 */
final class QuickCompile {
  static final String CLASSES_DIRECTORY = "classes";
  static final String SOURCES_FILE = "sources.txt";
  static final String STAMP_FILE = "classes.stamp";
  static final String TEMPORARY_DIRECTORY = "classes.tmp";
  private static final String OUTPUT_PREFIX = "# output ";
  private static final String JAVA_SOURCES = "src/main/java/";
  /** Services that make the compiler run code from the classpath: processors and plugins. */
  private static final List<String> COMPILER_SERVICES = List.of(
      "META-INF/services/javax.annotation.processing.Processor",
      "META-INF/services/com.sun.source.util.Plugin");
  /** Words in a build file that mean an annotation processor (Lombok, MapStruct, ...) runs. */
  private static final List<String> PROCESSOR_HINTS = List.of(
      "annotationprocessor", "<processor", "lombok", "kapt", "-processor", "-xplugin");
  private static final List<String> BUILD_FILES = List.of(
      "pom.xml", "build.gradle", "build.gradle.kts");

  /** What the last build tool run left: its class folder fingerprint and the sources. */
  private record Recorded(String output, Map<String, Long> sources) {}

  private final Path root;
  private final Path state;

  QuickCompile(Path root, Path state) {
    this.root = root;
    this.state = state;
  }

  /** The folder the build tool compiles Java classes into, relative to the project. */
  static String outputDirectory(ProjectBuild.Tool tool) {
    return tool == ProjectBuild.Tool.GRADLE ? "build/classes/java/main" : "target/classes";
  }

  /**
   * Records the sources the build tool just compiled and drops an older copy. Called after
   * every successful build tool run.
   */
  void recordBuild(ProjectBuild.Tool tool, Map<String, Long> sources) throws IOException {
    try {
      if (isPlainStateFolder(root, state)) {
        deleteTree(state.resolve(CLASSES_DIRECTORY));
      }
    } catch (IOException | UncheckedIOException e) {
      // Another Migrax process may hold it open; the stamp below stops its reuse anyway.
      Log.debug("Could not delete " + state.resolve(CLASSES_DIRECTORY) + ": " + e.getMessage());
    }
    Files.deleteIfExists(state.resolve(STAMP_FILE));
    StringBuilder text = new StringBuilder();
    text.append(OUTPUT_PREFIX).append(fingerprint(root.resolve(outputDirectory(tool))))
        .append('\n');
    sources.forEach((file, time) -> text.append(time).append('\t').append(file).append('\n'));
    Files.writeString(state.resolve(SOURCES_FILE), text, StandardCharsets.UTF_8);
  }

  /**
   * The compiled copy for these sources: the existing one when it was made from them and the
   * build tool's class folder has not changed since, otherwise a fresh one.
   *
   * @return the copy, or null when the build tool has to run instead
   */
  Path classes(ProjectBuild.Tool tool, Map<String, Long> sources, String sourceStamp,
               List<Path> dependencies) {
    Path classes = state.resolve(CLASSES_DIRECTORY);
    Path temporary = state.resolve(TEMPORARY_DIRECTORY);
    try {
      // One walk of the class folder per command: it can hold thousands of files.
      Path output = root.resolve(outputDirectory(tool));
      String outputPrint = fingerprint(output);
      String copyStamp = sourceStamp + " " + outputPrint;
      Path stamp = state.resolve(STAMP_FILE);
      if (Files.isDirectory(classes) && Files.isRegularFile(stamp)
          && Files.readString(stamp).trim().equals(copyStamp)) {
        return classes;
      }
      String reason = unsafe(sources, dependencies, outputPrint);
      if (reason != null) {
        Log.debug("Running the build tool: " + reason + ".");
        return null;
      }
      Integer release = releaseOf(output);
      if (release == null || release > Runtime.version().feature()) {
        Log.debug("Running the build tool: the classes need a newer Java than this one.");
        return null;
      }
      deleteTree(temporary);
      copyResources(output, temporary);
      List<Path> javaFiles = sources.keySet().stream()
          .filter(file -> file.startsWith(JAVA_SOURCES) && file.endsWith(".java"))
          .map(root::resolve)
          .toList();
      if (!javaFiles.isEmpty()
          && !run(javaFiles, temporary, output, dependencies, release)) {
        deleteTree(temporary);
        return null;
      }
      String missing = missingClass(output, temporary);
      if (missing != null) {
        Log.debug("Running the build tool: " + missing + " has no Java source here.");
        deleteTree(temporary);
        return null;
      }
      Files.deleteIfExists(stamp);
      deleteTree(classes);
      Files.move(temporary, classes, StandardCopyOption.ATOMIC_MOVE);
      Files.writeString(stamp, copyStamp);
      return classes;
    } catch (IOException | UncheckedIOException e) {
      // A locked or vanished file (another Migrax process, Windows): use the build tool.
      Log.debug("Running the build tool: " + e.getMessage());
      try {
        deleteTree(temporary);
      } catch (IOException | UncheckedIOException ignored) {
        // Left for the next run, which deletes it first.
      }
      return null;
    }
  }

  /** Why the quick path could differ from the build tool, or null when it is safe. */
  private String unsafe(Map<String, Long> sources, List<Path> dependencies, String output)
      throws IOException {
    Recorded built = readSources();
    if (built == null || built.output().isEmpty()) {
      return "no record of the last build";
    }
    if (!sources.keySet().containsAll(built.sources().keySet())) {
      return "a source file was deleted";
    }
    for (String file : sources.keySet()) {
      if (!file.startsWith(JAVA_SOURCES) || !file.endsWith(".java")) {
        return file + " is not a Java source";
      }
    }
    if (ToolProvider.getSystemJavaCompiler() == null) {
      return "this Java has no compiler (a JRE)";
    }
    if (!output.equals(built.output())) {
      return "the class folder changed since the last build";
    }
    for (String file : BUILD_FILES) {
      Path build = root.resolve(file);
      if (Files.isRegularFile(build)) {
        String text = Files.readString(build, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        for (String hint : PROCESSOR_HINTS) {
          if (text.contains(hint)) {
            return file + " sets up an annotation processor";
          }
        }
      }
    }
    for (Path dependency : dependencies) {
      if (hasProcessor(dependency)) {
        return dependency.getFileName() + " has an annotation processor";
      }
    }
    return null;
  }

  /** True when the entry could make the compiler run its code (or can't be read to tell). */
  private static boolean hasProcessor(Path dependency) {
    if (Files.isDirectory(dependency)) {
      return COMPILER_SERVICES.stream()
          .anyMatch(service -> Files.exists(dependency.resolve(service)));
    }
    if (!Files.isRegularFile(dependency)) {
      return false;
    }
    // Every file, not only *.jar: the compiler reads any zip on the classpath.
    try (ZipFile jar = new ZipFile(dependency.toFile())) {
      return COMPILER_SERVICES.stream().anyMatch(service -> jar.getEntry(service) != null);
    } catch (IOException e) {
      return true;
    }
  }

  private boolean run(List<Path> javaFiles, Path classes, Path output, List<Path> dependencies,
                      int release) throws IOException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    List<String> classpath = new ArrayList<>();
    for (String directory : ProjectContext.CLASS_DIRECTORIES) {
      Path path = root.resolve(directory);
      if (Files.isDirectory(path) && !path.equals(output)) {
        classpath.add(path.toString());
      }
    }
    dependencies.stream().filter(Files::exists).map(Path::toString).forEach(classpath::add);
    List<String> options = new ArrayList<>(List.of(
        "-d", classes.toString(),
        "-classpath", String.join(File.pathSeparator, classpath),
        "-encoding", "UTF-8",
        "-proc:none",
        "-implicit:none",
        "-parameters",
        "-nowarn"));
    if (release < Runtime.version().feature()) {
      options.add("--release");
      options.add(Integer.toString(release));
    }
    StringWriter log = new StringWriter();
    try (StandardJavaFileManager files =
             compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
      // An empty processor path: the compiler then looks for processors and plugins there
      // instead of on the classpath, so no code from a dependency runs inside Migrax.
      files.setLocationFromPaths(StandardLocation.ANNOTATION_PROCESSOR_PATH, List.of());
      Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(javaFiles);
      boolean compiled = compiler.getTask(log, files, null, options, null, units).call();
      if (!compiled) {
        Log.debug("Running the build tool, the Java compiler said: "
            + log.toString().lines().findFirst().orElse("(nothing)"));
      }
      return compiled;
    } catch (RuntimeException e) {
      // A compiler problem is never fatal here: the build tool runs instead.
      Log.debug("Running the build tool: the Java compiler failed: " + e);
      return false;
    }
  }

  /** The Java release the build tool compiled for, read from one class file; null if none. */
  private static Integer releaseOf(Path output) throws IOException {
    Path sample;
    try (Stream<Path> walk = Files.walk(output)) {
      sample = walk.filter(path -> path.toString().endsWith(".class")).findFirst().orElse(null);
    }
    if (sample == null) {
      return null;
    }
    try (InputStream stream = Files.newInputStream(sample);
         DataInputStream data = new DataInputStream(stream)) {
      if (data.readInt() != 0xCAFEBABE) {
        return null;
      }
      data.readUnsignedShort();
      int major = data.readUnsignedShort();
      // Major 52 is Java 8; the compiler of a current JDK accepts --release 8 and later.
      return major < 52 ? null : major - 44;
    }
  }

  /** A top-level class of the last build that the fresh copy lacks, or null. */
  private static String missingClass(Path output, Path copy) throws IOException {
    Set<String> compiled = new HashSet<>(classFiles(copy));
    for (String file : classFiles(output)) {
      if (!file.contains("$") && !compiled.contains(file)) {
        return file;
      }
    }
    return null;
  }

  private static List<String> classFiles(Path folder) throws IOException {
    try (Stream<Path> walk = Files.walk(folder)) {
      return walk.filter(path -> path.toString().endsWith(".class"))
          .map(path -> folder.relativize(path).toString().replace(File.separatorChar, '/'))
          .toList();
    }
  }

  private Recorded readSources() throws IOException {
    Path file = state.resolve(SOURCES_FILE);
    if (!Files.isRegularFile(file)) {
      return null;
    }
    String output = null;
    Map<String, Long> sources = new LinkedHashMap<>();
    for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
      if (line.startsWith(OUTPUT_PREFIX)) {
        output = line.substring(OUTPUT_PREFIX.length()).trim();
        continue;
      }
      int tab = line.indexOf('\t');
      if (tab > 0) {
        try {
          sources.put(line.substring(tab + 1), Long.parseLong(line.substring(0, tab)));
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return output == null ? null : new Recorded(output, sources);
  }

  /** Names, sizes and modification times of every file in the folder; "" when it is empty. */
  private static String fingerprint(Path folder) throws IOException {
    if (!Files.isDirectory(folder)) {
      return "";
    }
    MessageDigest digest = ProjectBuild.sha256();
    boolean empty = true;
    try (Stream<Path> walk = Files.walk(folder)) {
      for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
        empty = false;
        digest.update(folder.relativize(path).toString().getBytes(StandardCharsets.UTF_8));
        digest.update((Files.size(path) + "/" + Files.getLastModifiedTime(path).toMillis())
            .getBytes(StandardCharsets.UTF_8));
      }
    }
    return empty ? "" : HexFormat.of().formatHex(digest.digest());
  }

  /** Copies everything but class files: persistence.xml, orm.xml and other resources. */
  private static void copyResources(Path from, Path to) throws IOException {
    Files.createDirectories(to);
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path path : walk.toList()) {
        Path target = to.resolve(from.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else if (!path.toString().endsWith(".class")) {
          Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }

  /**
   * Deletes the folder and what is in it, but never goes through a link: a symbolic link or a
   * Windows junction (which Java reports as a directory) is removed itself, and what it
   * points to stays.
   */
  static void deleteTree(Path folder) throws IOException {
    BasicFileAttributes start;
    try {
      start = Files.readAttributes(folder, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    } catch (NoSuchFileException e) {
      return;
    }
    if (!start.isDirectory() || isLink(start)) {
      Files.delete(folder);
      return;
    }
    Files.walkFileTree(folder, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
          throws IOException {
        if (!directory.equals(folder) && isLink(attributes)) {
          Files.delete(directory);
          return FileVisitResult.SKIP_SUBTREE;
        }
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
          throws IOException {
        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(Path directory, IOException failure)
          throws IOException {
        if (failure != null) {
          throw failure;
        }
        Files.delete(directory);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /** A symbolic link, or a Windows junction (a directory that is also "other"). */
  private static boolean isLink(BasicFileAttributes attributes) {
    return attributes.isSymbolicLink() || attributes.isOther();
  }

  /** True when the folder is exactly root/.migrax, reached without any link on the way. */
  static boolean isPlainStateFolder(Path root, Path state) {
    try {
      BasicFileAttributes attributes =
          Files.readAttributes(state, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return attributes.isDirectory() && !isLink(attributes)
          && state.toRealPath().equals(root.toRealPath().resolve(state.getFileName()));
    } catch (IOException e) {
      return false;
    }
  }
}
