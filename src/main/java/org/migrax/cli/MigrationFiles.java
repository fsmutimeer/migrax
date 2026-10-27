package org.migrax.cli;

import org.migrax.runner.MigrationLoader;
import org.migrax.runner.MigrationRunner;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finding and numbering migration files in the migration folder. */
final class MigrationFiles {
  private static final Pattern JAVA_SOURCE = Pattern.compile("^V(\\d+)__\\w+\\.java$");

  private MigrationFiles() {}

  /** A migration file named on the command line, by file name, name without .sql or path. */
  static Path find(Project project, String name) throws Exception {
    for (String candidate : List.of(name, name + ".sql")) {
      Path file = project.migrationFile(candidate);
      if (file != null) {
        return file;
      }
    }
    for (Path candidate : List.of(project.root().resolve(name), Path.of(name))) {
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new UsageException("Migration file not found: " + name,
        "Run 'migrax status' to list migrations in " + project.displayMigrations() + ".");
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
