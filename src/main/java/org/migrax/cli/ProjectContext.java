package org.migrax.cli;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ProjectContext {
  private static final List<String> PROJECT_MARKERS = List.of(
      "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts");
  static final List<String> CLASS_DIRECTORIES = List.of(
      "target/classes",
      "build/classes/java/main",
      "build/classes/kotlin/main",
      "out/production/classes");
  private static final List<String> DEPENDENCY_DIRECTORIES = List.of(
      "target/dependency", "build/dependencies", "build/libs", "lib");

  private ProjectContext() {}

  static Path findRoot(Path start) throws IOException {
    Path current = start.toAbsolutePath().normalize();
    if (Files.isRegularFile(current)) {
      current = current.getParent();
    }
    for (Path directory = current; directory != null; directory = directory.getParent()) {
      for (String marker : PROJECT_MARKERS) {
        if (Files.exists(directory.resolve(marker))) {
          return directory;
        }
      }
      if (Files.exists(directory.resolve(".migrax"))) {
        return directory;
      }
    }
    return current;
  }

  static String packageName(Path root, String requested) throws Exception {
    if (requested != null && !requested.isBlank()) {
      return requested.trim();
    }
    return org.migrax.plugin.ProjectPackage.entityPackage(root);
  }

  static URLClassLoader runtimeLoader(Path root, String[] args) throws IOException {
    return runtimeLoader(root, explicitClasspath(args), List.of());
  }

  /**
   * JDBC driver jars that Migrax brings itself, for running without a project (the container
   * image ships some): every jar in MIGRAX_DRIVERS (or -Dmigrax.drivers), otherwise in the
   * {@code drivers} folder of the Migrax installation, when it exists.
   */
  static List<Path> driverJars() throws IOException {
    String configured = firstNonBlank(System.getProperty("migrax.drivers"),
        System.getenv("MIGRAX_DRIVERS"));
    Path folder = configured != null ? Path.of(configured) : installedDriversFolder();
    if (folder == null || !Files.isDirectory(folder)) {
      return List.of();
    }
    try (var children = Files.list(folder)) {
      return children.filter(Files::isRegularFile)
          .filter(child -> child.getFileName().toString().endsWith(".jar"))
          .map(child -> child.toAbsolutePath().normalize())
          .sorted()
          .toList();
    }
  }

  /** {@code <installation>/drivers}, next to the {@code lib} folder holding migrax.jar. */
  private static Path installedDriversFolder() {
    try {
      java.security.CodeSource source = ProjectContext.class.getProtectionDomain().getCodeSource();
      if (source == null || source.getLocation() == null) {
        return null;
      }
      Path jar = Path.of(source.getLocation().toURI());
      Path lib = jar.getParent();
      Path home = lib == null ? null : lib.getParent();
      return home == null ? null : home.resolve("drivers");
    } catch (Exception e) {
      return null;
    }
  }

  /** Returns the user-supplied classpath from --classpath, -Dmigrax.classpath or MIGRAX_CLASSPATH. */
  static String explicitClasspath(String[] args) {
    return firstNonBlank(
        option(args, "--classpath"),
        System.getProperty("migrax.classpath"), System.getenv("MIGRAX_CLASSPATH"));
  }

  /**
   * Builds the class loader used to read the application's entities and JDBC driver.
   *
   * @param explicit user-supplied path list, or null
   * @param resolved dependency entries resolved from the project's build tool
   */
  static URLClassLoader runtimeLoader(Path root, String explicit, List<Path> resolved)
      throws IOException {
    Set<Path> entries = new LinkedHashSet<>();
    if (explicit != null) {
      for (String item : explicit.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
        if (!item.isBlank()) {
          Path path = Path.of(item.trim());
          entries.add((path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize());
        }
      }
    }

    for (String directory : CLASS_DIRECTORIES) {
      Path path = root.resolve(directory);
      if (Files.isDirectory(path)) {
        entries.add(path.toAbsolutePath().normalize());
      }
    }
    // Fallback folders are scanned only when the build tool did not resolve dependencies:
    // build/libs usually holds the application's own packaged jar, which may be stale.
    for (String directory : resolved.isEmpty() ? DEPENDENCY_DIRECTORIES : List.<String>of()) {
      Path path = root.resolve(directory);
      if (Files.isDirectory(path)) {
        try (var children = Files.list(path)) {
          children.filter(Files::isRegularFile)
              .filter(child -> child.getFileName().toString().endsWith(".jar"))
              .map(child -> child.toAbsolutePath().normalize())
              .forEach(entries::add);
        }
      }
    }

    for (Path entry : resolved) {
      if (Files.exists(entry)) {
        entries.add(entry.toAbsolutePath().normalize());
      }
    }
    // Last, so the project's own driver versions win.
    entries.addAll(driverJars());

    List<URL> urls = new ArrayList<>();
    for (Path entry : entries) {
      if (!Files.exists(entry)) {
        throw new IOException("Configured runtime classpath entry does not exist: " + entry);
      }
      try {
        urls.add(entry.toUri().toURL());
      } catch (MalformedURLException e) {
        throw new IOException("Invalid runtime classpath entry: " + entry, e);
      }
    }
    return new URLClassLoader(urls.toArray(URL[]::new),
        Thread.currentThread().getContextClassLoader());
  }

  private static String option(String[] args, String key) {
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(key + "=")) {
        return args[i].substring(key.length() + 1);
      }
      if (args[i].equals(key) && i + 1 < args.length) {
        return args[i + 1];
      }
    }
    return null;
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) {
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }
}
