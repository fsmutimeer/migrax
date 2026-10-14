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

/** EclipseLink's own mapping of the test entities, read without a database. */
class EclipseLinkReaderTest {
  private static ModelExtractor.Result read(String dialect) throws Exception {
    return ModelExtractor.extract(Thread.currentThread().getContextClassLoader(),
        "org.migrax.hibernate.fixtures", NamingStrategy.JPA, Dialects.byName(dialect), Map.of(),
        ModelExtractor.Mode.ECLIPSELINK);
  }

  @Test
  void readsEclipseLinksNamesAndIdStrategies() throws Exception {
    ModelExtractor.Result result = read("postgresql");
    assertTrue(result.source().startsWith("EclipseLink 4."), result.source());
    SchemaModel model = result.model();

    // EclipseLink's own default names, in lower case: TAG, ID.
    SchemaModel.Table tag = model.table("tag");
    assertNotNull(tag, model.tables().toString());
    assertTrue(tag.column("id").identity(), "GenerationType.IDENTITY");

    // GenerationType.SEQUENCE: a database sequence that starts one block in.
    SchemaModel.Sequence orders = model.sequence("purchaseorderseq");
    assertEquals(20L, orders.allocationSize());
    assertEquals(20L, orders.initialValue());

    // A plain @GeneratedValue: a row in EclipseLink's SEQUENCE table.
    SchemaModel.Sequence defaults = model.sequence("SEQ_GEN");
    assertTrue(defaults.inTable());
    assertEquals("sequence", defaults.table());
    assertNotNull(model.table("sequence").column("seq_count"));
  }

  @Test
  void followsTheDatabase() throws Exception {
    // MySQL has no sequences: EclipseLink uses an identity column instead, and its table
    // names are case-sensitive, so they keep EclipseLink's spelling.
    SchemaModel model = read("mysql").model();
    assertEquals(null, model.sequence("purchaseorderseq"));
    assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("TAG")),
        model.tables().toString());
  }
}
