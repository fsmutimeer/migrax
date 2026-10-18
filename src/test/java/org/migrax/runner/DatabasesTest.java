package org.migrax.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings of several databases: migrax.databases.<name>.*. */
class DatabasesTest {
  private static final Map<String, String> SETTINGS = Map.of(
      "migrax.locations", "db/main",
      "migrax.schemas", "a,b",
      "migrax.placeholders.owner", "main_owner",
      "migrax.lock-timeout", "2m",
      "migrax.env", "prod",
      "migrax.databases.billing.datasource", "billingDataSource",
      "migrax.databases.billing.placeholders.owner", "billing_owner",
      "migrax.databases.audit.env", "audit-env",
      "migrax.databases.audit.locations", "db/audit");

  @Test
  void namesComeFromTheBlocks() {
    assertEquals(List.of("audit", "billing"), Databases.names(SETTINGS));
    assertEquals(List.of(), Databases.names(Map.of("migrax.locations", "db/migration")));
  }

  @Test
  void theDefaultDatabaseKeepsItsSettingsWithoutTheBlocks() {
    Map<String, String> main = Databases.settings(Databases.DEFAULT, SETTINGS);
    assertEquals("db/main", main.get("migrax.locations"));
    assertEquals("main_owner", main.get("migrax.placeholders.owner"));
    assertTrue(main.keySet().stream().noneMatch(k -> k.startsWith("migrax.databases.")));
    assertNull(Databases.dataSource(Databases.DEFAULT, SETTINGS));
  }

  @Test
  void aDatabaseHasItsOwnFoldersAndSharesTheRest() {
    Map<String, String> billing = Databases.settings("billing", SETTINGS);
    assertEquals("db/billing", billing.get("migrax.locations"));
    assertEquals("db.billing", billing.get("migrax.java-package"));
    assertNull(billing.get("migrax.schemas"), "schemas belong to one database");
    assertEquals("billing_owner", billing.get("migrax.placeholders.owner"));
    assertEquals("2m", billing.get("migrax.lock-timeout"));
    assertEquals("prod", billing.get("migrax.env"));
    assertEquals("billingDataSource", Databases.dataSource("billing", SETTINGS));

    Map<String, String> audit = Databases.settings("audit", SETTINGS);
    assertEquals("db/audit", audit.get("migrax.locations"));
    assertEquals("audit-env", audit.get("migrax.env"), "a block can set a shared setting");
    assertNull(audit.get("migrax.placeholders.owner"));
    assertEquals("audit", Databases.dataSource("audit", SETTINGS));
  }

  @Test
  void wrongNamesAreExplained() {
    IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
        () -> Databases.names(Map.of("migrax.databases.bill ing.locations", "x")));
    assertTrue(bad.getMessage().contains("migrax.databases.billing.locations"), bad.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> Databases.names(Map.of("migrax.databases.default.locations", "x")));
    assertThrows(IllegalArgumentException.class,
        () -> Databases.names(Map.of("migrax.databases.migration.locations", "x")));
  }
}
