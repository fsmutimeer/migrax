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
}
