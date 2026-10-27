package org.migrax.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
}
