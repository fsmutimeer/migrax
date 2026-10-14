package org.migrax.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A list of an application's migrations, written when it is built: the SQL files with their
 * classpath paths, and the Java migration classes. Where the classpath can't be listed (a
 * native executable), Migrax reads the migrations from this list instead of scanning folders
 * and packages. Each line is {@code folder <path>}, {@code sql <path>} or
 * {@code java <class name>}.
 *
 * @param folders the migration folders the list was made for, such as {@code db/migration}
 * @param sqlResources classpath paths of SQL migrations, such as {@code db/migration/0001_a.sql}
 * @param javaClasses class names of Java migrations
 * @since 0.6.0
 */
public record MigrationIndex(List<String> folders, List<String> sqlResources,
                             List<String> javaClasses) {
  /** Where the list lives on the classpath. */
  public static final String RESOURCE = "META-INF/migrax/migrations.idx";

  public MigrationIndex {
    folders = List.copyOf(folders);
    sqlResources = List.copyOf(sqlResources);
    javaClasses = List.copyOf(javaClasses);
  }

  /** The list's text. */
  public String format() {
    StringBuilder text = new StringBuilder("# Migrax migrations, written at build time\n");
    folders.forEach(folder -> text.append("folder ").append(folder).append('\n'));
    sqlResources.forEach(path -> text.append("sql ").append(path).append('\n'));
    javaClasses.forEach(name -> text.append("java ").append(name).append('\n'));
    return text.toString();
  }

  /** The list on {@code loader}'s classpath, or null when the application has none. */
  public static MigrationIndex read(ClassLoader loader) throws IOException {
    try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        return null;
      }
      List<String> folders = new ArrayList<>();
      List<String> sql = new ArrayList<>();
      List<String> java = new ArrayList<>();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        String entry = line.strip();
        if (entry.startsWith("folder ")) {
          folders.add(entry.substring(7).strip());
        } else if (entry.startsWith("sql ")) {
          sql.add(entry.substring(4).strip());
        } else if (entry.startsWith("java ")) {
          java.add(entry.substring(5).strip());
        }
      }
      return new MigrationIndex(folders, sql, java);
    }
  }

  /** Whether the list was made for a folder, so it holds all of that folder's files. */
  public boolean covers(String folder) {
    return folders.contains(folder);
  }

  /** The SQL files directly in a classpath folder such as {@code db/migration}. */
  public List<String> sqlIn(String folder) {
    String prefix = folder.isEmpty() ? "" : folder + "/";
    return sqlResources.stream().filter(path -> path.startsWith(prefix)
        && path.indexOf('/', prefix.length()) < 0 && path.endsWith(".sql")).toList();
  }

  /** The Java migrations in a package and its sub-packages. */
  public List<String> javaIn(String javaPackage) {
    String prefix = javaPackage.isEmpty() ? "" : javaPackage + ".";
    return javaClasses.stream().filter(name -> name.startsWith(prefix)).toList();
  }
}
