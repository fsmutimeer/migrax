package io.migrax.runner;

import io.migrax.api.JavaMigration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One migration: a SQL script or a {@link JavaMigration}.
 *
 * <ul>
 *   <li>{@link Kind#VERSIONED}: runs once, in version order ({@code 0001_initial.sql},
 *       {@code V0005__Backfill}).</li>
 *   <li>{@link Kind#REPEATABLE}: {@code R__name.sql}, re-run whenever its content changes,
 *       after all versioned migrations. Use it for views, functions and grants.</li>
 *   <li>{@link Kind#CALLBACK}: {@code beforeMigrate.sql}, {@code afterMigrate.sql},
 *       {@code beforeEachMigrate.sql} and {@code afterEachMigrate.sql}, run around migrations
 *       and never recorded in the history.</li>
 * </ul>
 *
 * @param version name recorded in the history, e.g. {@code 0002_add_email.sql}
 * @param kind what kind of migration this is
 * @param sql the SQL text, or null for Java migrations
 * @param checksum SHA-256 of the SQL text, or the Java migration's checksum
 * @param java the Java migration, or null for SQL
 * @since 0.1.0
 */
public record Migration(String version, Kind kind, String sql, String checksum,
                        JavaMigration java) {

  /** Migration kinds. */
  public enum Kind { VERSIONED, REPEATABLE, CALLBACK }

  /** File names of callback scripts, compatible with Flyway's. */
  public static final Set<String> CALLBACKS = Set.of(
      "beforeMigrate.sql", "afterMigrate.sql", "beforeEachMigrate.sql", "afterEachMigrate.sql");

  public Migration {
    Objects.requireNonNull(version, "version");
    Objects.requireNonNull(kind, "kind");
  }

  /** A SQL migration from its file name and content. */
  public static Migration ofSql(String fileName, String sql) {
    Kind kind = CALLBACKS.contains(fileName) ? Kind.CALLBACK
        : fileName.startsWith("R__") ? Kind.REPEATABLE : Kind.VERSIONED;
    return new Migration(fileName, kind, sql, sha256(sql), null);
  }

  /** A SQL migration read from a file. */
  public static Migration load(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      throw new IllegalArgumentException("Migration is not a regular file: " + file);
    }
    return ofSql(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
  }

  /** SQL migrations read from files. */
  public static List<Migration> load(List<Path> files) throws IOException {
    List<Migration> migrations = new java.util.ArrayList<>();
    for (Path file : files) {
      migrations.add(load(file));
    }
    return migrations;
  }

  /** A Java migration; its version is {@link JavaMigration#version()}. */
  public static Migration ofJava(JavaMigration migration) {
    return new Migration(migration.version(), Kind.VERSIONED, null, migration.checksum(),
        migration);
  }

  public boolean isJava() {
    return java != null;
  }

  /** Versions this migration replaces, from a {@code -- migrax:replaces a.sql,b.sql} line. */
  public List<String> replaces() {
    return sql == null ? List.of() : SqlScript.directiveValues(sql, "replaces");
  }

  static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }
}
