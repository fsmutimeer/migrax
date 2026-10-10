package io.migrax;

import io.migrax.dialect.MigrationLockHeldException;
import io.migrax.runner.DatabaseMigrationLock;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Waiting for the migration lock (--lock-timeout). */
class MigrationLockTest {
  private static final String URL = "jdbc:h2:mem:lock_wait;DB_CLOSE_DELAY=-1";

  /** Holds the lock on another thread for {@code millis}, then releases it. */
  private static Thread holdFor(long millis, CountDownLatch held) {
    Thread holder = new Thread(() -> {
      try (Connection connection = DriverManager.getConnection(URL);
           AutoCloseable lock = DatabaseMigrationLock.acquire(connection)) {
        held.countDown();
        Thread.sleep(millis);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    });
    holder.start();
    return holder;
  }

  @Test
  void waitsForTheLockUntilItIsReleased() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    Thread holder = holdFor(1000, held);
    held.await();
    try (Connection connection = DriverManager.getConnection(URL)) {
      assertThrows(MigrationLockHeldException.class,
          () -> DatabaseMigrationLock.acquire(connection), "no timeout: fails at once");
      long start = System.nanoTime();
      try (AutoCloseable lock = DatabaseMigrationLock.acquire(connection, Duration.ofSeconds(10))) {
        assertTrue(System.nanoTime() - start >= 500_000_000L, "it waited for the holder");
      }
    }
    holder.join();
  }

  @Test
  void givesUpAfterTheTimeout() throws Exception {
    CountDownLatch held = new CountDownLatch(1);
    Thread holder = holdFor(3000, held);
    held.await();
    try (Connection connection = DriverManager.getConnection(URL)) {
      MigrationLockHeldException error = assertThrows(MigrationLockHeldException.class,
          () -> DatabaseMigrationLock.acquire(connection, Duration.ofMillis(700)));
      assertTrue(error.getMessage().contains("Waited 700ms"), error.getMessage());
    }
    holder.join();
  }

  @Test
  void readsDurations() {
    assertEquals(Duration.ofSeconds(30), DatabaseMigrationLock.parseTimeout("30s"));
    assertEquals(Duration.ofMinutes(2), DatabaseMigrationLock.parseTimeout(" 2m "));
    assertEquals(Duration.ofMillis(500), DatabaseMigrationLock.parseTimeout("500ms"));
    assertEquals(Duration.ofHours(1), DatabaseMigrationLock.parseTimeout("1h"));
    assertEquals(Duration.ofSeconds(45), DatabaseMigrationLock.parseTimeout("45"));
    assertThrows(IllegalArgumentException.class, () -> DatabaseMigrationLock.parseTimeout("soon"));
  }
}
