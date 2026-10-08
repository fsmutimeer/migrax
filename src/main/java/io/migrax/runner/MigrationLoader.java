package io.migrax.runner;

import io.migrax.api.JavaMigration;
import io.migrax.util.ClassScanner;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collects the migrations of a project: SQL files in the migration folder and
 * {@link JavaMigration} classes in the Java migrations package.
 *
 * @since 0.1.0
 */
public final class MigrationLoader {
  /** Default package for Java migrations, as in Flyway. */
  public static final String DEFAULT_JAVA_PACKAGE = "db.migration";

  /** Sub-folder of the migration folder that holds rollback scripts. */
  public static final String ROLLBACK_FOLDER = "rollback";

  private MigrationLoader() {}

  /** SQL files directly in {@code folder}, sorted; sub-folders such as rollback/ are ignored. */
  public static List<Path> sqlFiles(Path folder) throws Exception {
    if (!Files.isDirectory(folder)) {
      return List.of();
    }
    try (Stream<Path> paths = Files.list(folder)) {
      return paths.filter(path -> path.getFileName().toString().endsWith(".sql"))
          .filter(Files::isRegularFile)
          .sorted(MigrationRunner.MIGRATION_PATH_COMPARATOR)
          .toList();
    }
  }

  /**
   * SQL migrations from {@code folder} plus Java migrations found on {@code loader}.
   *
   * @param loader class loader with the application classes, or null to skip Java migrations
   * @param javaPackage package to scan for Java migrations, or null for the default
   */
  public static List<Migration> load(Path folder, ClassLoader loader, String javaPackage)
      throws Exception {
    List<Migration> migrations = new ArrayList<>(Migration.load(sqlFiles(folder)));
    if (loader != null) {
      migrations.addAll(javaMigrations(loader,
          javaPackage == null || javaPackage.isBlank() ? DEFAULT_JAVA_PACKAGE : javaPackage));
    }
    return migrations;
  }

  /** Instantiates every concrete {@link JavaMigration} in the package. */
  public static List<Migration> javaMigrations(ClassLoader loader, String javaPackage)
      throws Exception {
    List<Migration> migrations = new ArrayList<>();
    for (Class<?> type : ClassScanner.scan(loader, javaPackage,
        c -> JavaMigration.class.isAssignableFrom(c) && !c.isInterface()
            && !Modifier.isAbstract(c.getModifiers()))) {
      JavaMigration migration = (JavaMigration) type.getDeclaredConstructor().newInstance();
      migrations.add(Migration.ofJava(migration));
    }
    return migrations;
  }

  /** The rollback script for a migration file, or null if there is none. */
  public static Path rollbackScript(Path folder, String version) {
    Path script = folder.resolve(ROLLBACK_FOLDER).resolve(version);
    return Files.isRegularFile(script) ? script : null;
  }
}
