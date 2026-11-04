package org.migrax.cli;

import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finding and numbering migration files in the migration folder. */
final class MigrationFiles {
  private static final Pattern JAVA_SOURCE = Pattern.compile("^V(\\d+)__\\w+\\.java$");

  /** A file name, or rollback/ and a file name: no other folder, no parent, no drive. */
  private static final Pattern LOCAL_NAME =
      Pattern.compile("(?:rollback/)?(?!\\.\\.?$)[^/\\\\:]+");

  private MigrationFiles() {}

  /** A migration file named on the command line, by file name, name without .sql or path. */
  static Path find(Project project, String name) throws Exception {
    Path migration = inMigrationFolders(project, name);
    if (migration != null) {
      return migration;
    }
    for (Path candidate : List.of(project.root().resolve(name), Path.of(name))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new UsageException("Migration file not found: " + name,
        "Run 'migrax status' to list migrations in " + project.displayMigrations() + ".");
  }

  /**
   * A migration or rollback script by file name, and nothing outside the migration folders:
   * for callers that must not reach the project's other files (the MCP server).
   */
  static Path findMigration(Project project, String name) throws Exception {
    Path migration = LOCAL_NAME.matcher(name).matches() ? inMigrationFolders(project, name) : null;
    if (migration == null || !isMigrationFile(project, migration)) {
      throw new UsageException("Migration file not found: " + name,
          "Pass a file name from 'migrax status', or rollback/<file name>.");
    }
    return migration;
  }

  /**
   * Refuses a migration or rollback script that is really a file outside the migration folders,
   * before anything reads it: a database error can quote what it was given. For callers that
   * must not reach the project's other files (the MCP server).
   */
  static void requireInFolders(Project project) throws Exception {
    List<Path> files = new ArrayList<>(MigrationLoader.sqlFiles(project.migrationFolders()));
    for (Path folder : project.migrationFolders()) {
      files.addAll(MigrationLoader.sqlFiles(folder.resolve("rollback")));
    }
    for (Path file : files) {
      if (!isMigrationFile(project, file)) {
        throw new UsageException(project.display(file) + " leads outside the migration folders.",
            "Replace the link with the file itself.");
      }
    }
  }

  /** Refuses a schema file that is really something else than SQL in the project folder. */
  static void requireSchemaFile(Project project, Path file) throws Exception {
    if (Files.exists(file)) {
      Path real = file.toRealPath();
      if (!real.getFileName().toString().endsWith(".sql")
          || !real.startsWith(project.root().toRealPath())) {
        throw new UsageException(project.display(file) + " is not a SQL file in the project.",
            "Point migrax.schema-file at the schema's .sql file.");
      }
    }
  }

  /**
   * Whether the real file, after links and short names, is a migration in a migration folder. A
   * folder can hold other files, be the project folder itself, or be a link to the real folder.
   */
  private static boolean isMigrationFile(Project project, Path file) throws Exception {
    Path real;
    try {
      real = file.toRealPath();
    } catch (IOException e) {
      return false;
    }
    if (!real.getFileName().toString().endsWith(".sql") && !isJavaMigration(real)) {
      return false;
    }
    for (Path folder : project.migrationFolders()) {
      if (Files.isDirectory(folder) && real.startsWith(folder.toRealPath())) {
        return true;
      }
    }
    return false;
  }

  private static Path inMigrationFolders(Project project, String name) throws Exception {
    for (String candidate : List.of(name, name + ".sql")) {
      Path file = project.migrationFile(candidate);
      if (file != null) {
        return file;
      }
    }
    return null;
  }

  /** Whether the file is a Java migration source such as V0003__SplitFullName.java. */
  static boolean isJavaMigration(Path source) {
    return JAVA_SOURCE.matcher(source.getFileName().toString()).matches();
  }

  /** The number of a Java migration source, which may be larger than an int. */
  static BigInteger javaNumber(Path source) {
    Matcher matcher = JAVA_SOURCE.matcher(source.getFileName().toString());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Not a Java migration: " + source);
    }
    return new BigInteger(matcher.group(1));
  }

  /** The next migration number after the highest one in the folder. */
  static BigInteger nextNumber(Path folder) throws Exception {
    return nextNumber(MigrationLoader.sqlFiles(folder));
  }

  /**
   * The number after the highest one of these SQL files and Java migration sources. A
   * BigInteger: date-style numbers such as 20261027120000 are common and must not wrap around
   * to a small number that would run before the others.
   */
  static BigInteger nextNumber(List<Path> files) {
    BigInteger highest = BigInteger.ZERO;
    for (Path file : files) {
      BigInteger number = number(file);
      if (number != null) {
        highest = highest.max(number);
      }
    }
    return highest.add(BigInteger.ONE);
  }

  /** The number of a SQL migration or Java migration source; null when it has none. */
  static BigInteger number(Path file) {
    if (isJavaMigration(file)) {
      return javaNumber(file);
    }
    List<BigInteger> version = MigrationRunner.versionParts(file.getFileName().toString());
    return version.isEmpty() ? null : version.get(0);
  }
}
