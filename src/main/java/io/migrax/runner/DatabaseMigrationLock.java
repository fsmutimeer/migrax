package io.migrax.runner;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.dialect.MigrationLockHeldException;
import io.migrax.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The lock that keeps two Migrax processes from migrating the same database at once. Each
 * {@link Dialect} implements it with its database's own locks
 * ({@link Dialect#acquireMigrationLock}).
 */
public final class DatabaseMigrationLock {

  private DatabaseMigrationLock() {}

  /** Takes the lock, or fails at once when another process holds it. */
  public static AutoCloseable acquire(Connection connection) throws SQLException {
    return acquire(connection, Duration.ZERO);
  }

  /**
   * Takes the lock, waiting up to {@code timeout} while another process holds it (for example
   * when several instances of an application start together).
   *
   * @since 0.3.0
   */
  public static AutoCloseable acquire(Connection connection, Duration timeout)
      throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    String resource = lockName(connection);
    Log.debug("Acquiring migration lock for {} on {}", resource, product);
    Dialect dialect = Dialects.forConnection(connection).orElseThrow(() -> new SQLException(
        "Concurrent migration locking is not implemented for database " + product
            + "; refusing to proceed."));
    long deadline = System.nanoTime() + timeout.toNanos();
    long pause = 250;
    boolean announced = false;
    while (true) {
      try {
        return dialect.acquireMigrationLock(connection, resource);
      } catch (MigrationLockHeldException held) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          if (timeout.isZero()) {
            throw held;
          }
          MigrationLockHeldException waited = new MigrationLockHeldException(
              held.getMessage() + " Waited " + describe(timeout) + " (--lock-timeout).");
          waited.initCause(held);
          throw waited;
        }
        if (!announced) {
          Log.info("Another Migrax process holds the migration lock; waiting up to {}...",
              describe(timeout));
          announced = true;
        }
        try {
          Thread.sleep(Math.min(pause, TimeUnit.NANOSECONDS.toMillis(left) + 1));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw held;
        }
        pause = Math.min(pause * 2, 2000);
      }
    }
  }

  /**
   * The lock timeout set with {@code -Dmigrax.lockTimeout} or {@code MIGRAX_LOCK_TIMEOUT}, or
   * zero (don't wait).
   *
   * @since 0.3.0
   */
  public static Duration configuredTimeout() {
    String value = System.getProperty("migrax.lockTimeout");
    if (value == null || value.isBlank()) {
      value = System.getenv("MIGRAX_LOCK_TIMEOUT");
    }
    return value == null || value.isBlank() ? Duration.ZERO : parseTimeout(value);
  }

  /**
   * Reads a duration such as {@code 30s}, {@code 2m}, {@code 500ms} or {@code 1h}; a plain
   * number means seconds.
   *
   * @throws IllegalArgumentException for anything else
   * @since 0.3.0
   */
  public static Duration parseTimeout(String value) {
    Matcher matcher = DURATION.matcher(value.trim().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Invalid lock timeout '" + value
          + "'. Use a duration such as 30s, 2m or 500ms.");
    }
    long amount = Long.parseLong(matcher.group(1));
    return switch (matcher.group(2) == null ? "s" : matcher.group(2)) {
      case "ms" -> Duration.ofMillis(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofSeconds(amount);
    };
  }

  private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h)?");

  private static String describe(Duration duration) {
    long millis = duration.toMillis();
    return millis % 60_000 == 0 && millis > 0 ? millis / 60_000 + "m"
        : millis % 1000 == 0 ? millis / 1000 + "s" : millis + "ms";
  }

  private static String lockName(Connection connection) throws SQLException {
    String database = connection.getCatalog();
    if (database == null || database.isBlank()) {
      database = connection.getMetaData().getURL();
    }
    return "io.migrax:" + HexFormat.of().formatHex(sha256(database), 0, 16);
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }
}
