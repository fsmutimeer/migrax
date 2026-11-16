package org.migrax.dialect;

/**
 * The SQL that limits how long a session's statements wait for a lock that another session
 * holds, such as PostgreSQL's {@code lock_timeout}; see {@link Dialect#tableLockTimeout()}.
 *
 * @since 0.19.0
 */
public interface TableLockTimeoutSql {
  /** The query whose single value is the session's current limit, as text. */
  String read();

  /**
   * The statement that sets the limit.
   *
   * @param timeout the longest wait, more than zero
   */
  String set(java.time.Duration timeout);

  /** The statement that sets the limit back to a value {@link #read()} returned. */
  String restore(String value);

  /**
   * Whether a statement failed because it stopped waiting for a lock: the limit ran out, or
   * the statement asked not to wait at all ({@code NOWAIT}).
   */
  boolean isTimeout(java.sql.SQLException failure);
}
