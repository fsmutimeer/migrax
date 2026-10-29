package org.migrax.cli;

import org.junit.jupiter.api.Test;
import org.migrax.model.SchemaModel;
import org.migrax.ops.AddColumn;
import org.migrax.ops.DropTable;
import org.migrax.ops.RenameColumn;
import org.migrax.ops.RenameTable;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReportsTest {
  private static final Map<String, String> ENTITIES =
      Map.of("customer", "com.example.Customer", "client", "com.example.Client");

  @Test
  void operationsNameTheirColumnAndEntity() {
    var add = new AddColumn("customer", new SchemaModel.Column("phone", "varchar(30)", true,
        30, null, null, null, false, false, null, null));
    assertEquals("phone", Reports.column(add));
    assertEquals("com.example.Customer", Reports.entity(add, ENTITIES));
    // The new name of a renamed column, and the entity of a table's new name.
    assertEquals("mail", Reports.column(new RenameColumn("customer", "email", "mail")));
    assertEquals("com.example.Client",
        Reports.entity(new RenameTable("customer", "client"), ENTITIES));
  }

  @Test
  void tableNamesMatchWithoutCaseAndDroppedTablesHaveNoEntity() {
    var add = new AddColumn("CUSTOMER", new SchemaModel.Column("phone", "varchar(30)", true,
        30, null, null, null, false, false, null, null));
    assertEquals("com.example.Customer", Reports.entity(add, ENTITIES));
    assertNull(Reports.entity(new DropTable("orders"), ENTITIES));
    assertNull(Reports.entity(new DropTable("CUSTOMER"), ENTITIES));
    assertNull(Reports.column(new DropTable("orders")));
    assertNull(Reports.entity(add, Map.of()));
  }
}
