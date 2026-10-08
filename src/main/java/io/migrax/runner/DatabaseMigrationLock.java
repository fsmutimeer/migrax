package io.migrax.runner;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import io.migrax.util.Log;

public final class DatabaseMigrationLock {
  private static final ConcurrentHashMap<String, ReentrantLock> H2_LOCKS = new ConcurrentHashMap<>();

  private DatabaseMigrationLock() {}

  public static AutoCloseable acquire(Connection connection) throws SQLException {
    String product = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
    String resource = lockName(connection);
    Log.debug("Acquiring migration lock for {} on {}", resource, product);
    if (product.contains("mysql") || product.contains("mariadb")) {
      try (PreparedStatement statement = connection.prepareStatement("SELECT GET_LOCK(?, 0)")) {
        statement.setString(1, resource);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || result.getInt(1) != 1) {
            throw new SQLException("Another Migrax process is applying migrations.");
          }
        }
      }
      return () -> {
        try (PreparedStatement statement =
                 connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
          statement.setString(1, resource);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
              throw new SQLException("Could not release the MySQL migration lock.");
            }
          }
        }
      };
    }
    if (product.contains("postgresql")) {
      long key = lockKey(resource);
      try (PreparedStatement statement =
               connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
        statement.setLong(1, key);
        try (ResultSet result = statement.executeQuery()) {
          if (!result.next() || !result.getBoolean(1)) {
            throw new SQLException("Another Migrax process is applying migrations.");
          }
        }
      }
      return () -> {
        try (PreparedStatement statement =
                 connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
          statement.setLong(1, key);
          try (ResultSet result = statement.executeQuery()) {
            if (!result.next() || !result.getBoolean(1)) {
              throw new SQLException("Could not release the PostgreSQL migration lock.");
            }
          }
        }
      };
    }
    if (product.contains("microsoft sql server")) {
      acquireSqlServerLock(connection, resource);
      return () -> releaseSqlServerLock(connection, resource);
    }
    if (product.contains("oracle")) {
      int key = Math.floorMod(lockKey(resource), 1_073_741_823) + 1;
      try (CallableStatement statement =
               connection.prepareCall("{? = call DBMS_LOCK.REQUEST(?, 6, 0, FALSE)}")) {
        statement.registerOutParameter(1, Types.INTEGER);
        statement.setInt(2, key);
        statement.execute();
        int status = statement.getInt(1);
        if (status != 0 && status != 4) {
          throw new SQLException("Oracle migration lock request failed with status " + status
              + ". The service account may need EXECUTE on DBMS_LOCK.");
        }
      }
      return () -> {
        try (CallableStatement statement =
                 connection.prepareCall("{? = call DBMS_LOCK.RELEASE(?)}")) {
          statement.registerOutParameter(1, Types.INTEGER);
          statement.setInt(2, key);
          statement.execute();
          int status = statement.getInt(1);
          if (status != 0) {
            throw new SQLException("Oracle migration lock release failed with status " + status);
          }
        }
      };
    }
    if (product.contains("h2")) {
      String url = connection.getMetaData().getURL();
      ReentrantLock localLock = H2_LOCKS.computeIfAbsent(url, ignored -> new ReentrantLock());
      if (!localLock.tryLock()) {
        throw new SQLException("Another Migrax invocation in this JVM is applying migrations.");
      }
      return localLock::unlock;
    }
    throw new SQLException("Concurrent migration locking is not implemented for database "
        + connection.getMetaData().getDatabaseProductName() + "; refusing to proceed.");
  }

  private static void acquireSqlServerLock(Connection connection, String resource)
      throws SQLException {
    String sql = "DECLARE @result int; "
        + "EXEC @result = sp_getapplock @Resource=?, @LockMode='Exclusive', "
        + "@LockOwner='Session', @LockTimeout=0; SELECT @result";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, resource);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) < 0) {
          throw new SQLException("Another Migrax process is applying migrations.");
        }
      }
    }
  }

  private static void releaseSqlServerLock(Connection connection, String resource)
      throws SQLException {
    String sql = "DECLARE @result int; "
        + "EXEC @result = sp_releaseapplock @Resource=?, @LockOwner='Session'; SELECT @result";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, resource);
      try (ResultSet result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) < 0) {
          throw new SQLException("Could not release the SQL Server migration lock.");
        }
      }
    }
  }

  private static String lockName(Connection connection) throws SQLException {
    String database = connection.getCatalog();
    if (database == null || database.isBlank()) {
      database = connection.getMetaData().getURL();
    }
    return "io.migrax:" + HexFormat.of().formatHex(sha256(database), 0, 16);
  }

  private static long lockKey(String resource) {
    return ByteBuffer.wrap(sha256(resource)).getLong();
  }

  private static byte[] sha256(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable.", e);
    }
  }
}
