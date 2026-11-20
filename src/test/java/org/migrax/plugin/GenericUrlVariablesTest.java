package org.migrax.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericUrlVariablesTest {

  private static final String POSTGRES = "postgres://shop:secret@db:5432/shop";
  private final List<String> skipped = new ArrayList<>();

  /** The main database's URL with only these environment variables and config keys. */
  private String url(Map<String, String> environment, Map<String, String> config) {
    skipped.clear();
    return ProjectDatabaseConfig.url(config, Map.of(), environment::get, skipped);
  }

  @Test
  void aGenericVariableCountsOnlyWithAJdbcUrl() {
    assertTrue(ProjectDatabaseConfig.usableUrl("DATABASE_URL", "jdbc:postgresql://db/shop"));
    // Upper case worked before: it must keep working.
    assertTrue(ProjectDatabaseConfig.usableUrl("DATABASE_URL", "JDBC:sqlite:shop.db"));
    assertTrue(ProjectDatabaseConfig.usableUrl("DB_URL", "jdbc:mysql://db/shop"));
    // What other languages' tools set: skipped, so the next setting is read.
    assertFalse(ProjectDatabaseConfig.usableUrl("DATABASE_URL", POSTGRES));
    assertFalse(ProjectDatabaseConfig.usableUrl("DATABASE_URL", "sqlite:///shop.db"));
    assertFalse(ProjectDatabaseConfig.usableUrl("DB_URL", "mysql://u:p@db/shop"));
    assertFalse(ProjectDatabaseConfig.usableUrl("DB_URL", "jdbc"));
    // Migrax's own and the frameworks' variables keep today's behavior, even when wrong.
    assertTrue(ProjectDatabaseConfig.usableUrl("MIGRAX_DATABASE_URL", POSTGRES));
    assertTrue(ProjectDatabaseConfig.usableUrl("SPRING_DATASOURCE_URL", POSTGRES));
  }

  @Test
  void aSkippedVariableLetsTheNextSettingIn() {
    Map<String, String> config = Map.of("migrax.url", "jdbc:sqlite:from-config.db");
    assertEquals("jdbc:sqlite:from-config.db", url(Map.of("DATABASE_URL", POSTGRES), config));
    assertEquals(List.of("DATABASE_URL"), skipped);

    assertEquals("jdbc:mysql://db/shop",
        url(Map.of("DATABASE_URL", POSTGRES, "DB_URL", "jdbc:mysql://db/shop"), config));
    assertEquals(List.of("DATABASE_URL"), skipped);

    assertEquals("jdbc:sqlite:from-config.db",
        url(Map.of("DATABASE_URL", POSTGRES, "DB_URL", "sqlite:///x.db"), config));
    assertEquals(List.of("DATABASE_URL", "DB_URL"), skipped);

    assertNull(url(Map.of("DATABASE_URL", POSTGRES), Map.of()));
    assertEquals(List.of("DATABASE_URL"), skipped);
  }

  @Test
  void aVariableNotReachedIsNotReportedAsSkipped() {
    // An earlier setting wins: DATABASE_URL is never read, so doctor must not mention it.
    assertEquals("jdbc:sqlite:migrax.db", url(Map.of(
        "MIGRAX_DATABASE_URL", "jdbc:sqlite:migrax.db", "DATABASE_URL", POSTGRES), Map.of()));
    assertEquals(List.of(), skipped);
    assertEquals("jdbc:sqlite:spring.db", url(Map.of(
        "SPRING_DATASOURCE_URL", "jdbc:sqlite:spring.db", "DATABASE_URL", POSTGRES), Map.of()));
    assertEquals(List.of(), skipped);
    // DATABASE_URL is a JDBC URL and wins: DB_URL after it is never read.
    assertEquals("jdbc:sqlite:a.db",
        url(Map.of("DATABASE_URL", "jdbc:sqlite:a.db", "DB_URL", POSTGRES), Map.of()));
    assertEquals(List.of(), skipped);
    skipped.clear();
    assertEquals("jdbc:sqlite:option.db", ProjectDatabaseConfig.url(Map.of(),
        Map.of("url", "jdbc:sqlite:option.db"), Map.of("DATABASE_URL", POSTGRES)::get, skipped));
    assertEquals(List.of(), skipped);
  }

  @Test
  void aSystemPropertyOrAUrlFileWinsWithoutASkip(@TempDir Path folder) throws Exception {
    System.setProperty("migrax.url", "jdbc:sqlite:property.db");
    try {
      assertEquals("jdbc:sqlite:property.db", url(Map.of("DATABASE_URL", POSTGRES), Map.of()));
      assertEquals(List.of(), skipped);
    } finally {
      System.clearProperty("migrax.url");
    }
    Path file = Files.writeString(folder.resolve("url"), "jdbc:sqlite:from-file.db\n");
    assertEquals("jdbc:sqlite:from-file.db", url(Map.of(
        "MIGRAX_DATABASE_URL_FILE", file.toString(), "DATABASE_URL", POSTGRES), Map.of()));
    assertEquals(List.of(), skipped);
  }

  @Test
  void placeholdersAreResolvedFirstAsBefore() {
    System.setProperty("migrax.test.jdbc", "jdbc:sqlite:placeholder.db");
    try {
      assertEquals("jdbc:sqlite:placeholder.db",
          url(Map.of("DATABASE_URL", "${migrax.test.jdbc}"), Map.of()));
      assertEquals(List.of(), skipped);
    } finally {
      System.clearProperty("migrax.test.jdbc");
    }
    // A placeholder that resolves to nothing stops the lookup, as it always did.
    assertNull(url(Map.of("DATABASE_URL", "${MIGRAX_TEST_UNSET_VARIABLE}"),
        Map.of("migrax.url", "jdbc:sqlite:from-config.db")));
    assertEquals(List.of(), skipped);
  }
}
