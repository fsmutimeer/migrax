package org.migrax.model;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps Hibernate from logging the URL it connects to, password included, which it does at INFO.
 * The level is shared by the whole JVM, so overlapping validations hold it together and the last
 * one restores it. This reaches Hibernate when it logs through java.util.logging, as it does in
 * the CLI; a project's own logging setup (Log4j, Logback) decides for itself.
 */
final class HibernateConnectionLog {
  /** Kept: a logger nothing refers to can lose its level. */
  private static final Logger LOG = Logger.getLogger("org.hibernate");

  private static int holders;
  private static boolean changed;
  private static Level previous;

  private HibernateConnectionLog() {}

  /** Raises the level to WARNING when it is lower; every call needs a {@link #release}. */
  static synchronized void hold() {
    if (holders++ == 0) {
      previous = LOG.getLevel();
      changed = previous == null || previous.intValue() < Level.WARNING.intValue();
      if (changed) {
        LOG.setLevel(Level.WARNING);
      }
    }
  }

  /** The last holder puts back the level it found. */
  static synchronized void release() {
    if (--holders == 0 && changed) {
      LOG.setLevel(previous);
    }
  }
}
