package io.migrax;

import io.migrax.dialect.MySqlDialect;
import io.migrax.model.JpaExtractor;
import io.migrax.ops.AddColumn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class JpaExtractorColumnDefinitionTest {

  @Test
  void doesNotTreatExplicitColumnDefinitionAsDefaultValue() throws Exception {
    var schema = new JpaExtractor().extract("io.migrax.model.fixtures");
    var column = schema.table("column_definition_test").column("description");

    assertNotNull(column);
    assertEquals("TEXT", column.sqlType());
    assertNull(column.defaultValue());
    assertEquals(
        "ALTER TABLE column_definition_test ADD COLUMN description TEXT NOT NULL",
        new MySqlDialect().render(new AddColumn("column_definition_test", column)));
  }
}
