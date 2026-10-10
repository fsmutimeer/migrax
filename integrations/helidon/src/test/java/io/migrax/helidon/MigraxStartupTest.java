package io.migrax.helidon;

import io.helidon.microprofile.testing.junit5.HelidonTest;
import io.migrax.helidon.app.MemoService;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Hibernate validates the schema (hbm2ddl.auto=validate) on an empty database, so writing a
 * Memo only works when Migrax migrated the data source persistence.xml names.
 */
@HelidonTest
class MigraxStartupTest {
  @Inject
  MemoService memos;

  @Inject
  @Named("other")
  DataSource other;

  @Test
  void migratesTheDataSourceThatJpaUses() throws Exception {
    assertEquals(1L, memos.addAndCount("hello"));

    // The other data source was left alone.
    try (Connection connection = other.getConnection();
         ResultSet tables = connection.getMetaData().getTables(null, null, "MIGRAX_HISTORY",
             null)) {
      assertFalse(tables.next());
    }
  }

  @Inject
  @org.eclipse.microprofile.health.Readiness
  MigraxReadinessCheck readiness;

  @Test
  void reportsReadiness() {
    var response = readiness.call();
    assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.UP,
        response.getStatus(), String.valueOf(response.getData()));
    assertEquals(0L, response.getData().orElseThrow().get("pending"));
  }
}
