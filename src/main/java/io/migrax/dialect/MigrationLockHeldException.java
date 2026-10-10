package io.migrax.dialect;

import java.sql.SQLException;

/**
 * Another process holds the migration lock ({@link Dialect#acquireMigrationLock}). Unlike other
 * lock failures it is worth waiting for: Migrax tries again until its lock timeout passes.
 *
 * @since 0.3.0
 */
public class MigrationLockHeldException extends SQLException {
  private static final long serialVersionUID = 1L;

  public MigrationLockHeldException(String message) {
    super(message);
  }
}
