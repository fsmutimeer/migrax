package io.migrax;

import io.migrax.model.SchemaModel;
import io.migrax.util.Json;
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
}
