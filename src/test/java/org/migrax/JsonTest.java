package org.migrax;

import org.migrax.model.SchemaModel;
import org.migrax.util.Json;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonTest {
  @Test
  void roundTripsPrimaryKeyConstraintName() {
    var schema = new SchemaModel(List.of(new SchemaModel.Table(
        "users",
        List.of(),
        new SchemaModel.PrimaryKey(List.of("id"), "users_custom_pk"),
        List.of(),
        List.of())));

    SchemaModel restored = Json.parse(Json.write(schema));

    assertEquals("users_custom_pk", restored.table("users").primaryKey().constraintName());
  }

  @Test
  void readsSnapshotsWithoutPrimaryKeyConstraintName() {
    String oldSnapshot = """
        {"tables":[{"name":"users","columns":[],"primaryKey":{"columns":["id"]},
        "indexes":[],"foreignKeys":[]}],"sequences":[]}
        """;

    SchemaModel restored = Json.parse(oldSnapshot);

    assertEquals(List.of("id"), restored.table("users").primaryKey().columns());
    assertNull(restored.table("users").primaryKey().constraintName());
  }

  @Test
  void throwsDescriptiveErrorOnMalformedOrTruncatedJson() {
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Json.parse(""));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Json.parse("{\"tables\":"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Json.parse("{\"tables\": [}"));
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> Json.parse("{\"tables\": [{\"name\": \"unclosed"));
  }

  @Test
  void escapesCarriageReturnAndTabInSnapshotStrings() {
    var schema = new SchemaModel(List.of(new SchemaModel.Table(
        "special_table",
        List.of(new SchemaModel.Column(
            "col", "varchar", true, null, null, null, "'val\rwith\ttab'", false, false, null, "varchar")),
        null,
        List.of(),
        List.of())));

    String json = Json.write(schema);
    org.junit.jupiter.api.Assertions.assertTrue(json.contains("\\r"));
    org.junit.jupiter.api.Assertions.assertTrue(json.contains("\\t"));

    SchemaModel restored = Json.parse(json);
    assertEquals("'val\rwith\ttab'", restored.table("special_table").columns().get(0).defaultValue());
  }

  @Test
  void recordsTheServerVersionOnlyWhenKnown() throws Exception {
    java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("migrax-json");
    try {
      java.nio.file.Path oracle = dir.resolve("oracle.json");
      org.migrax.diff.SnapshotStore.save(oracle, SchemaModel.empty(), "oracle", null, "23.26");
      assertEquals("23.26", org.migrax.diff.SnapshotStore.server(oracle));
      assertEquals("oracle", org.migrax.diff.SnapshotStore.dialect(oracle));
      // Without a version the snapshot is written exactly as before.
      assertEquals(Json.write(SchemaModel.empty(), "postgresql", "spring"),
          Json.write(SchemaModel.empty(), "postgresql", "spring", null));
      java.nio.file.Path postgres = dir.resolve("postgres.json");
      org.migrax.diff.SnapshotStore.save(postgres, SchemaModel.empty(), "postgresql", null, null);
      assertNull(org.migrax.diff.SnapshotStore.server(postgres));
    } finally {
      try (var files = java.nio.file.Files.walk(dir)) {
        files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }
}
