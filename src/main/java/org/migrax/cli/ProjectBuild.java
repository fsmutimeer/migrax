package org.migrax.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs the project's own build tool so the CLI works without manual classpath setup.
 *
 * <p>For Maven and Gradle projects it compiles the application when sources changed and
 * resolves the runtime dependency classpath (JPA API, Hibernate, JDBC driver). The resolved
 * classpath is cached in {@code .migrax/classpath.txt} and refreshed only when a build file
 * changes, so repeated commands stay fast.
 */
final class ProjectBuild {
  static final String CLASSPATH_FILE = "classpath.txt";
  static final String CLASSPATH_HASH_FILE = "classpath.hash";
  static final String BUILD_STAMP_FILE = "build.stamp";

  private static final List<String> MAVEN_BUILD_FILES = List.of("pom.xml");
  private static final List<String> GRADLE_BUILD_FILES = List.of(
      "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
      "gradle.properties", "gradle/libs.versions.toml");
  private static final List<String> SOURCE_DIRECTORIES = List.of(
      "src/main/java", "src/main/kotlin");
  private static final long TIMEOUT_MINUTES = 15;
  private static final String IGNORE_HEADER =
      "# Machine-local Migrax cache files. Keep snapshot.json under version control.";
  private static final List<String> IGNORED = List.of(
      "classpath.txt", "classpath.txt.new", "classpath.hash", "build.stamp",
      "migrax-classpath.gradle", QuickCompile.SOURCES_FILE, QuickCompile.STAMP_FILE,
      QuickCompile.CLASSES_DIRECTORY + "/",
      QuickCompile.TEMPORARY_DIRECTORY + "/");

  enum Tool { MAVEN, GRADLE, NONE }

  /** What the caller needs from the build. */
  record Request(boolean compile, boolean refresh) {}

  /**
   * Resolved dependency entries plus a human-readable summary of what happened. When the
   * edited files were compiled without the build tool, {@code classes} holds them and stands
   * in for the build tool's class folder {@code replaces}; both are null otherwise.
   */
  record Result(Tool tool, List<Path> dependencies, List<String> notes, Path classes,
                Path replaces) {
    Result(Tool tool, List<Path> dependencies, List<String> notes) {
      this(tool, dependencies, notes, null, null);
    }
  }

  private final Path root;
  private final PrintStream status;

  ProjectBuild(Path root, PrintStream status) {
    this.root = root;
    this.status = status;
  }

  static Tool detect(Path root) {
    if (Files.isRegularFile(root.resolve("pom.xml"))) {
      return Tool.MAVEN;
    }
    if (Files.isRegularFile(root.resolve("build.gradle"))
        || Files.isRegularFile(root.resolve("build.gradle.kts"))) {
      return Tool.GRADLE;
    }
    return Tool.NONE;
  }

  Result prepare(Request request) throws IOException {
    Tool tool = detect(root);
    List<String> notes = new ArrayList<>();
    if (tool == Tool.NONE) {
      notes.add("No pom.xml or build.gradle found; using compiled classes and jars found in "
          + "standard folders. Set MIGRAX_CLASSPATH if dependencies are missing.");
      return new Result(tool, List.of(), notes);
    }

    Path state = ensureStateDirectory(root);
    Path classpathFile = state.resolve(CLASSPATH_FILE);
    Path hashFile = state.resolve(CLASSPATH_HASH_FILE);
    Path stampFile = state.resolve(BUILD_STAMP_FILE);

    String buildHash = buildFilesHash(tool);
    boolean classpathCurrent = !request.refresh()
        && Files.isRegularFile(classpathFile)
        && Files.isRegularFile(hashFile)
        && Files.readString(hashFile).trim().equals(buildHash)
        && readClasspath(classpathFile).stream().allMatch(Files::exists);

    Map<String, Long> sources = request.compile() ? sourceFiles() : null;
    String sourceStamp = sources == null ? null : stamp(sources);
    boolean classesCurrent = !request.compile()
        || (sourceStamp != null
            && Files.isRegularFile(stampFile)
            && Files.readString(stampFile).trim().equals(sourceStamp)
            && hasCompiledClasses());

    if (classpathCurrent && classesCurrent) {
      return new Result(tool, readClasspath(classpathFile), notes);
    }
    // Only in a .migrax that is a plain folder of this project: the quick path deletes folders
    // in it, and a link (or a Windows junction) would point those deletes somewhere else.
    if (classpathCurrent && !quickCompileDisabled()
        && QuickCompile.isPlainStateFolder(root, state)) {
      // Only sources changed: compile them directly, without the build tool.
      QuickCompile quick = new QuickCompile(root, state);
      List<Path> dependencies = readClasspath(classpathFile);
      Path classes = quick.classes(tool, sources, sourceStamp, dependencies);
      if (classes != null) {
        notes.add("Compiled the Java sources into " + classes + " without "
            + (tool == Tool.MAVEN ? "Maven" : "Gradle") + ".");
        return new Result(tool, dependencies, notes, classes,
            root.resolve(QuickCompile.outputDirectory(tool)));
      }
    }

    List<String> work = new ArrayList<>();
    if (!classesCurrent) {
      work.add("compiling");
    }
    if (!classpathCurrent) {
      work.add("resolving dependencies");
    }
    String toolName = tool == Tool.MAVEN ? "Maven" : "Gradle";
    status.println("migrax: " + String.join(" and ", work) + " with " + toolName
        + (classpathCurrent ? "..." : " (the first run can take a minute)..."));

    // Write to a temporary file so a failed build keeps the last good cached classpath.
    Path freshClasspath = state.resolve(CLASSPATH_FILE + ".new");
    Files.deleteIfExists(freshClasspath);
    List<String> command = tool == Tool.MAVEN
        ? mavenCommand(!classesCurrent, freshClasspath)
        : gradleCommand(!classesCurrent, freshClasspath, state);
    run(command, toolName);

    if (!Files.isRegularFile(freshClasspath)) {
      throw new IOException(toolName + " finished but did not write the dependency classpath to "
          + freshClasspath + ". Set MIGRAX_CLASSPATH manually, or pass --classpath.");
    }
    Files.move(freshClasspath, classpathFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    Files.writeString(hashFile, buildHash);
    if (request.compile()) {
      Map<String, Long> built = sourceFiles();
      Files.writeString(stampFile, stamp(built));
      new QuickCompile(root, state).recordBuild(tool, built);
    }
    return new Result(tool, readClasspath(classpathFile), notes);
  }

  /** MIGRAX_QUICK_COMPILE=false (or -Dmigrax.quick-compile=false) always runs the build tool. */
  private static boolean quickCompileDisabled() {
    String value = System.getProperty("migrax.quick-compile",
        System.getenv("MIGRAX_QUICK_COMPILE"));
    return "false".equalsIgnoreCase(value == null ? null : value.trim());
  }

  /** Creates {@code .migrax/} with a .gitignore that keeps machine-local files out of git. */
  static Path ensureStateDirectory(Path root) throws IOException {
    Path state = root.resolve(".migrax");
    Files.createDirectories(state);
    Path ignore = state.resolve(".gitignore");
    if (!Files.exists(ignore)) {
      Files.writeString(ignore, IGNORE_HEADER + "\n" + String.join("\n", IGNORED) + "\n");
    } else {
      // Folders set up by an older version: add the cache files it didn't know yet.
      String text = Files.readString(ignore, StandardCharsets.UTF_8);
      if (text.startsWith(IGNORE_HEADER)) {
        List<String> lines = text.lines().map(String::trim).toList();
        List<String> missing = IGNORED.stream().filter(line -> !lines.contains(line)).toList();
        if (!missing.isEmpty()) {
          Files.writeString(ignore, (text.endsWith("\n") ? text : text + "\n")
              + String.join("\n", missing) + "\n", StandardCharsets.UTF_8);
        }
      }
    }
    return state;
  }


  /**
   * The dependency classpath saved by an earlier build, for --no-build. Entries that no longer
   * exist are skipped; empty when nothing was cached.
   */
  static List<Path> cachedClasspath(Path root) throws IOException {
    Path file = root.resolve(".migrax").resolve(CLASSPATH_FILE);
    if (!Files.isRegularFile(file)) {
      return List.of();
    }
    return readClasspath(file).stream().filter(Files::exists).toList();
  }

  static List<Path> readClasspath(Path file) throws IOException {
    String content = Files.readString(file, StandardCharsets.UTF_8).trim();
    List<Path> entries = new ArrayList<>();
    if (content.isEmpty()) {
      return entries;
    }
    for (String item : content.split("[" + java.util.regex.Pattern.quote(File.pathSeparator)
        + "\\r\\n]+")) {
      if (!item.isBlank()) {
        entries.add(Path.of(item.trim()));
      }
    }
    return entries;
  }

  private List<String> mavenCommand(boolean compile, Path classpathFile) throws IOException {
    List<String> command = new ArrayList<>();
    command.add(executable("mvnw", "mvn"));
    command.add("-q");
    command.add("-B");
    if (compile) {
      command.add("compile");
    }
    command.add("dependency:build-classpath");
    command.add("-Dmdep.outputFile=" + classpathFile.toAbsolutePath());
    command.add("-Dmdep.includeScope=runtime");
    return command;
  }

  private List<String> gradleCommand(boolean compile, Path classpathFile, Path state)
      throws IOException {
    Path initScript = state.resolve("migrax-classpath.gradle");
    Files.writeString(initScript, """
        // Written by Migrax: writes the main runtime classpath of the project at -Dmigrax.root.
        allprojects { p ->
          def target = new File(System.getProperty('migrax.root')).canonicalFile
          if (p.projectDir.canonicalFile == target) {
            p.tasks.register('migraxClasspath') {
              doLast {
                def files = p.sourceSets.main.runtimeClasspath.files.collect { it.absolutePath }
                new File(System.getProperty('migrax.output')).text =
                    files.join(File.pathSeparator)
              }
            }
          }
        }
        """);
    List<String> command = new ArrayList<>();
    command.add(executable("gradlew", "gradle"));
    command.add("-q");
    command.add("--console=plain");
    command.add("-I");
    command.add(initScript.toAbsolutePath().toString());
    command.add("-Dmigrax.root=" + root.toAbsolutePath());
    command.add("-Dmigrax.output=" + classpathFile.toAbsolutePath());
    if (compile) {
      command.add("classes");
    }
    command.add("migraxClasspath");
    return command;
  }

  /** Prefers the project's wrapper script, then the tool on PATH. */
  private String executable(String wrapper, String tool) throws IOException {
    boolean windows = isWindows();
    Path wrapperScript = root.resolve(windows ? wrapper + (wrapper.equals("mvnw") ? ".cmd" : ".bat")
        : wrapper);
    if (Files.isRegularFile(wrapperScript)) {
      return wrapperScript.toAbsolutePath().toString();
    }
    List<String> names = windows ? List.of(tool + ".cmd", tool + ".bat", tool + ".exe")
        : List.of(tool);
    String path = System.getenv("PATH");
    if (path != null) {
      for (String directory : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
        for (String name : names) {
          if (directory.isBlank()) {
            continue;
          }
          Path candidate = Path.of(directory.trim()).resolve(name);
          if (Files.isRegularFile(candidate)) {
            return candidate.toString();
          }
        }
      }
    }
    String home = System.getenv(tool.equals("mvn") ? "MAVEN_HOME" : "GRADLE_HOME");
    if (home != null && !home.isBlank()) {
      for (String name : names) {
        Path candidate = Path.of(home, "bin", name);
        if (Files.isRegularFile(candidate)) {
          return candidate.toString();
        }
      }
    }
    throw new IOException("Could not find " + tool + " or a ./" + wrapper + " wrapper. Install "
        + tool + ", add it to PATH, or pass --no-build with --classpath / MIGRAX_CLASSPATH.");
  }

  private void run(List<String> command, String toolName) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command)
        .directory(root.toFile())
        .redirectErrorStream(true);
    Process process;
    try {
      process = builder.start();
    } catch (IOException e) {
      throw new IOException("Could not start " + toolName + " (" + command.get(0) + "): "
          + e.getMessage(), e);
    }
    process.getOutputStream().close();
    byte[] output;
    try (InputStream stream = process.getInputStream()) {
      output = stream.readAllBytes();
    }
    try {
      if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
        process.destroyForcibly();
        throw new IOException(toolName + " did not finish within " + TIMEOUT_MINUTES + " minutes.");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new IOException("Interrupted while waiting for " + toolName + ".", e);
    }
    if (process.exitValue() != 0) {
      String log = new String(output, StandardCharsets.UTF_8).strip();
      throw new IOException(toolName + " build failed (exit code " + process.exitValue()
          + "). Fix the build, or pass --no-build to skip it.\n" + tail(log, 40));
    }
  }

  private static String tail(String text, int lines) {
    List<String> all = text.lines().toList();
    return String.join(System.lineSeparator(),
        all.subList(Math.max(0, all.size() - lines), all.size()));
  }

  private String buildFilesHash(Tool tool) throws IOException {
    List<String> files = tool == Tool.MAVEN ? MAVEN_BUILD_FILES : GRADLE_BUILD_FILES;
    MessageDigest digest = sha256();
    for (String file : files) {
      Path path = root.resolve(file);
      digest.update(file.getBytes(StandardCharsets.UTF_8));
      if (Files.isRegularFile(path)) {
        digest.update(Files.readAllBytes(path));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  /** Source files (relative path with forward slashes) and their modification times. */
  private Map<String, Long> sourceFiles() throws IOException {
    Map<String, Long> sources = new LinkedHashMap<>();
    for (String directory : SOURCE_DIRECTORIES) {
      Path folder = root.resolve(directory);
      if (!Files.isDirectory(folder)) {
        continue;
      }
      List<Path> files;
      try (Stream<Path> walk = Files.walk(folder)) {
        files = walk.filter(Files::isRegularFile).sorted().toList();
      }
      for (Path file : files) {
        sources.put(root.relativize(file).toString().replace(File.separatorChar, '/'),
            Files.getLastModifiedTime(file).toMillis());
      }
    }
    return sources;
  }

  /** Fingerprint of source files: names and modification times, so edits and deletes count. */
  private static String stamp(Map<String, Long> sources) {
    MessageDigest digest = sha256();
    sources.forEach((file, time) -> {
      // The platform separator, as earlier versions wrote it, so existing stamps stay valid.
      digest.update(file.replace('/', File.separatorChar).getBytes(StandardCharsets.UTF_8));
      digest.update(Long.toString(time).getBytes(StandardCharsets.UTF_8));
    });
    return HexFormat.of().formatHex(digest.digest());
  }

  private boolean hasCompiledClasses() {
    return ProjectContext.CLASS_DIRECTORIES.stream()
        .map(root::resolve)
        .anyMatch(Files::isDirectory);
  }

  static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
