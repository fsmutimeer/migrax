package org.migrax.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HibernateConnectionLogTest {
  private static final Logger LOG = Logger.getLogger("org.hibernate");
  private final Level before = LOG.getLevel();

  @AfterEach
  void restore() {
    LOG.setLevel(before);
  }

  @Test
  void anUnsetLevelIsRaisedAndPutBack() {
    LOG.setLevel(null);
    HibernateConnectionLog.hold();
    assertEquals(Level.WARNING, LOG.getLevel());
    HibernateConnectionLog.release();
    assertNull(LOG.getLevel());
  }

  @Test
  void aLowLevelIsRaisedAndPutBack() {
    LOG.setLevel(Level.FINE);
    HibernateConnectionLog.hold();
    assertEquals(Level.WARNING, LOG.getLevel());
    HibernateConnectionLog.release();
    assertEquals(Level.FINE, LOG.getLevel());
  }

  @Test
  void aStricterLevelIsLeftAlone() {
    LOG.setLevel(Level.SEVERE);
    HibernateConnectionLog.hold();
    assertEquals(Level.SEVERE, LOG.getLevel());
    HibernateConnectionLog.release();
    assertEquals(Level.SEVERE, LOG.getLevel());
  }

  @Test
  void overlappingHoldersRestoreTheFirstLevelOnce() {
    LOG.setLevel(null);
    HibernateConnectionLog.hold();
    HibernateConnectionLog.hold();
    HibernateConnectionLog.release();
    assertEquals(Level.WARNING, LOG.getLevel());
    HibernateConnectionLog.release();
    assertNull(LOG.getLevel());
  }
}
