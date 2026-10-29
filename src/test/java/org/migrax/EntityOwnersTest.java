package org.migrax;

import org.junit.jupiter.api.Test;
import org.migrax.dialect.Dialects;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The entity class that owns each table, as Hibernate's mapping and the scan report it. */
class EntityOwnersTest {

  @Test
  void hibernateAndTheScanNameTheClassOfEachTable() throws Exception {
    for (var mode : List.of(ModelExtractor.Mode.HIBERNATE, ModelExtractor.Mode.ANNOTATIONS)) {
      Map<String, String> entities = ModelExtractor.extract(
          Thread.currentThread().getContextClassLoader(), "org.migrax.hibernate.fixtures",
          NamingStrategy.SPRING, Dialects.byName("postgresql"), Map.of(), mode).entities();
      assertEquals("org.migrax.hibernate.fixtures.Customer", entities.get("customer"),
          mode + ": " + entities);
      assertEquals("org.migrax.hibernate.fixtures.PurchaseOrder",
          entities.get("purchase_order"), mode + ": " + entities);
      // A single-table hierarchy points to its root, not to a subclass.
      assertEquals("org.migrax.hibernate.fixtures.Payment", entities.get("payment"),
          mode + ": " + entities);
      assertFalse(entities.containsValue("org.migrax.hibernate.fixtures.CardPayment"),
          mode + ": " + entities);
    }
  }
}
