package org.migrax.diff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationsTest {
  @TempDir
  Path folder;

  @Test
  void numbersFollowTheHighestFileEvenAboveAnInt() throws Exception {
    Files.writeString(folder.resolve("0001_initial.sql"), "");
    assertEquals("0002_auto", Migrations.nextName(folder, false, List.of()).substring(0, 9));
    // A numbered file too long for an int crashed nextName; a V file wrapped to a small number.
    Files.writeString(folder.resolve("99999999999_far.sql"), "");
    assertEquals("100000000000_", Migrations.nextName(folder, false, List.of()).substring(0, 13));
    Files.writeString(folder.resolve("V4294967297000__farther.sql"), "");
    assertEquals("4294967297001_",
        Migrations.nextName(folder, false, List.of()).substring(0, 14));
  }
}
