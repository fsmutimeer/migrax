package org.migrax.runner;

import java.time.Duration;

/**
 * How long a migration statement waits for a lock that another session holds on a table
 * (PostgreSQL's {@code lock_timeout}). A quick {@code ALTER TABLE} that waits behind a long
 * query holds up every later query on that table, so Migrax gives up after a few seconds
 * instead, and the migration can run again when the table is less busy.
 *
 * @since 0.19.0
 */
public final class TableLockTimeout {
  /** Five seconds: longer than a quick schema change needs, shorter than most query timeouts. */
  public static final Duration DEFAULT = Duration.ofSeconds(5);

  private TableLockTimeout() {
  }

  /**
   * The timeout set with {@code -Dmigrax.table-lock-timeout} or
   * {@code MIGRAX_TABLE_LOCK_TIMEOUT}, else {@link #DEFAULT}; zero waits as long as the
   * database does.
   *
   * @throws IllegalArgumentException when the setting isn't a duration
   */
  public static Duration configured() {
    String value = System.getProperty("migrax.table-lock-timeout");
    if (value == null || value.isBlank()) {
      value = System.getenv("MIGRAX_TABLE_LOCK_TIMEOUT");
    }
    return value == null || value.isBlank() ? DEFAULT : parse(value);
  }

  /**
   * Reads a duration such as {@code 5s}, {@code 30s}, {@code 500ms} or {@code 1m}; a plain
   * number means seconds and {@code 0} turns the timeout off.
   *
   * @throws IllegalArgumentException for anything else
   */
  public static Duration parse(String value) {
    try {
      return DatabaseMigrationLock.parseTimeout(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Invalid table lock timeout '" + value
          + "'. Use a duration such as 5s, 30s or 1m, or 0 to wait as long as the database "
          + "does.", e);
    }
  }

  static String describe(Duration timeout) {
    return DatabaseMigrationLock.describe(timeout);
  }
}
