package io.migrax.runner;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.Dialects;
import io.migrax.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * The lock that keeps two Migrax processes from migrating the same database at once. Each
 * {@link Dialect} implements it with its database's own locks
 * ({@link Dialect#acquireMigrationLock}).
 */
public final class DatabaseMigrationLock {

  private DatabaseMigrationLock() {}

  public static AutoCloseable acquire(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName();
    String resource = lockName(connection);
    Log.debug("Acquiring migration lock for {} on {}", resource, product);
    Dialect dialect = Dialects.forConnection(connection).orElseThrow(() -> new SQLException(
        "Concurrent migration locking is not implemented for database " + product
            + "; refusing to proceed."));
    return dialect.acquireMigrationLock(connection, resource);
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
