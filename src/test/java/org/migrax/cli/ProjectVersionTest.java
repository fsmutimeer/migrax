package org.migrax.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectVersionTest {
  @Test
  void readsMajorAndMinorVersion() {
    assertArrayEquals(new int[] {16, 4}, Project.parseVersion("16.4"));
    assertArrayEquals(new int[] {8, 0}, Project.parseVersion("8.0"));
  }

  @Test
  void ignoresVersionsItCannotRead() {
    assertArrayEquals(new int[0], Project.parseVersion(null));
    assertArrayEquals(new int[0], Project.parseVersion("16"));
    assertArrayEquals(new int[0], Project.parseVersion("16.4.1"));
    assertArrayEquals(new int[0], Project.parseVersion("99999999999.0"));
    assertArrayEquals(new int[0], Project.parseVersion("1.99999999999"));
  }

  @Test
  void theVersionLookupWaitsTenSecondsAtMost() {
    assertEquals("10000", Project.versionLookupTimeout("jdbc:mysql://db:3306/shop")
        .getProperty("connectTimeout"));
    assertEquals("10000", Project.versionLookupTimeout("jdbc:mariadb://db/shop")
        .getProperty("connectTimeout"));
    assertEquals("10", Project.versionLookupTimeout("jdbc:postgresql://db/shop")
        .getProperty("connectTimeout"));
    assertEquals("10", Project.versionLookupTimeout("jdbc:postgresql://db/shop")
        .getProperty("loginTimeout"));
    assertEquals("10", Project.versionLookupTimeout("jdbc:yugabytedb://db:5433/shop")
        .getProperty("loginTimeout"));
    assertEquals("10000", Project.versionLookupTimeout("jdbc:oracle:thin:@db:1521/FREEPDB1")
        .getProperty("oracle.net.CONNECT_TIMEOUT"));
  }

  @Test
  void theVersionLookupKeepsATimeoutFromTheUrl() {
    assertTrue(Project.versionLookupTimeout("jdbc:mysql://db/shop?connectTimeout=60000")
        .isEmpty());
    assertTrue(Project.versionLookupTimeout("jdbc:h2:mem:x").isEmpty());
    // Other timeouts in the URL don't replace the connect timeout.
    assertEquals("10000", Project.versionLookupTimeout("jdbc:mysql://db/shop?socketTimeout=5000")
        .getProperty("connectTimeout"));
  }
}
