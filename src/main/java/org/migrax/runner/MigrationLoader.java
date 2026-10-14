package org.migrax.runner;

import org.migrax.api.JavaMigration;
import org.migrax.util.ClassScanner;

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
  /** Default package for Java migrations. */
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
   * SQL files directly in each folder, in version order across all of them.
   *
   * @since 0.5.0
   */
  public static List<Path> sqlFiles(List<Path> folders) throws Exception {
    List<Path> files = new ArrayList<>();
    for (Path folder : folders) {
      files.addAll(sqlFiles(folder));
    }
    files.sort(MigrationRunner.MIGRATION_PATH_COMPARATOR);
    return files;
  }

  /**
   * SQL migrations from every folder plus Java migrations found on {@code loader}.
   *
   * @since 0.5.0
   */
  public static List<Migration> load(List<Path> folders, ClassLoader loader, String javaPackage)
      throws Exception {
    List<Migration> migrations = new ArrayList<>(Migration.load(sqlFiles(folders)));
    if (loader != null) {
      migrations.addAll(javaMigrations(loader,
          javaPackage == null || javaPackage.isBlank() ? DEFAULT_JAVA_PACKAGE : javaPackage));
    }
    return migrations;
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
    MigrationIndex index = MigrationIndex.read(loader);
    if (index != null) {
      // Written at build time, for classpaths that can't be listed (native executables).
      for (String name : index.javaIn(javaPackage)) {
        Class<?> type = Class.forName(name, true, loader);
        migrations.add(Migration.ofJava(
            (JavaMigration) type.getDeclaredConstructor().newInstance()));
      }
      return migrations;
    }
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
