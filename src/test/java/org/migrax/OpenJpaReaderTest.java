package org.migrax;

import org.migrax.dialect.Dialects;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import org.migrax.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OpenJPA's own mapping of the test entities, read without a database. */
class OpenJpaReaderTest {
  private static ModelExtractor.Result read(String dialect) throws Exception {
    return ModelExtractor.extract(Thread.currentThread().getContextClassLoader(),
        "org.migrax.hibernate.fixtures", NamingStrategy.JPA, Dialects.byName(dialect), Map.of(),
        ModelExtractor.Mode.OPENJPA);
  }

  @Test
  void readsOpenJpasNamesAndIdStrategies() throws Exception {
    ModelExtractor.Result result = read("postgresql");
    assertTrue(result.source().startsWith("OpenJPA 4."), result.source());
    SchemaModel model = result.model();

    // OpenJPA's default names, in lower case: the class name for the table.
    SchemaModel.Table tag = model.table("tag");
    assertNotNull(tag, model.tables().toString());
    assertTrue(tag.column("id").identity(), "GenerationType.IDENTITY");

    // GenerationType.SEQUENCE: a database sequence.
    SchemaModel.Sequence orders = model.sequence("purchaseorderseq");
    assertNotNull(orders, model.sequences().toString());
    assertEquals(20L, orders.allocationSize());

    // A plain @GeneratedValue: OpenJPA's sequence table, whose row OpenJPA adds itself.
    assertNotNull(model.table("openjpa_sequence_table"), model.tables().toString());
  }

  @Test
  void keepsTableNamesWhereTheyAreCaseSensitive() throws Exception {
    SchemaModel model = read("mysql").model();
    assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("Tag")),
        model.tables().toString());
  }
}
