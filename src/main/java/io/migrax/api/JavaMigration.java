package io.migrax.api;

import java.sql.Connection;

/**
 * A migration written in Java, for data changes that are awkward in SQL (Django's
 * {@code RunPython}). Implementations need a public no-argument constructor and live in the
 * Java migrations package ({@code db.migration} by default). The class name gives the version
 * and order, like a SQL file name: {@code V0005__BackfillFullNames} runs after
 * {@code 0004_add_full_name.sql}.
 *
 * <pre>{@code
 * package db.migration;
 *
 * public class V0005__BackfillFullNames implements JavaMigration {
 *   public void migrate(Connection connection) throws Exception {
 *     try (var statement = connection.createStatement()) {
 *       statement.executeUpdate("UPDATE customer SET full_name = first_name || ' ' || last_name");
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>The migration runs in the same transaction as its history record. Do not commit or close
 * the connection.
 *
 * @since 0.1.0
 */
public interface JavaMigration {

  /** Applies the migration. */
  void migrate(Connection connection) throws Exception;

  /**
   * Reverts the migration for {@code migrax rollback}. The default refuses, because data
   * changes usually cannot be undone automatically.
   */
  default void rollback(Connection connection) throws Exception {
    throw new UnsupportedOperationException(
        getClass().getSimpleName() + " does not implement rollback(Connection).");
  }

  /** Version recorded in the migration history; defaults to the simple class name. */
  default String version() {
    return getClass().getSimpleName();
  }

  /**
   * Checksum recorded in the history. Change it to tell Migrax the migration was edited; the
   * default never changes.
   */
  default String checksum() {
    return "java:" + getClass().getName();
  }
}
