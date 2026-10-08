package io.migrax;

import io.migrax.model.SchemaModel;
import io.migrax.verify.SchemaComparator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaComparatorTest {

  private static SchemaModel model(SchemaModel.Column... columns) {
    return new SchemaModel(List.of(new SchemaModel.Table("products", List.of(columns),
        null, List.of(), List.of())));
  }

  private static SchemaModel.Column text(String name, String logical, Integer length) {
    return new SchemaModel.Column(name, logical, false, length, null, null, null, false, false,
        null, logical);
  }

  private static SchemaModel.Column decimal(String name, int precision, int scale) {
    return new SchemaModel.Column(name, "decimal", false, null, precision, scale, null, false,
        false, null, "decimal");
  }

  @Test
  void reportsColumnsNarrowedOutsideMigrations() {
    // null length is JPA's default of 255.
    SchemaModel expected = model(text("name", "varchar", null), text("body", "text", null),
        decimal("price", 10, 2));
    SchemaModel actual = model(text("name", "varchar", 100), text("body", "varchar", 4000),
        decimal("price", 8, 2));

    List<String> found = SchemaComparator.compare(expected, actual, "mysql").stream()
        .map(SchemaComparator.Difference::object).toList();

    assertEquals(List.of("products.name", "products.body", "products.price"), found);
  }

  @Test
  void acceptsWiderColumnsAndUnreportedLengths() {
    SchemaModel expected = model(text("name", "varchar", null), decimal("price", 10, 2));
    SchemaModel actual = model(text("name", "varchar", 500), decimal("price", 12, 4));
    assertTrue(SchemaComparator.compare(expected, actual, "mysql").isEmpty());

    // H2 and PostgreSQL report text columns as varchar(2147483647).
    assertTrue(SchemaComparator.compare(model(text("notes", "text", null)),
        model(text("notes", "varchar", Integer.MAX_VALUE)), "h2").isEmpty());

    // Some drivers report 0 or -1 for unbounded varchar.
    assertTrue(SchemaComparator.compare(model(text("name", "nvarchar", 50)),
        model(text("name", "nvarchar", -1)), "sqlserver").isEmpty());
  }
}
