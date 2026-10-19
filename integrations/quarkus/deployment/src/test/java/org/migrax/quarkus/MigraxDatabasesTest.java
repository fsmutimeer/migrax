package org.migrax.quarkus;

import db.billing.V0002__SeedInvoice;
import db.migration.V0002__AddNote;
import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.migrax.quarkus.app.Note;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A second database, the named data source billing: its own folder (db/billing), Java package
 * (db.billing) and placeholders; readiness and the pending gauge report it.
 */
class MigraxDatabasesTest {
  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar.addClasses(Note.class, V0002__AddNote.class,
              V0002__SeedInvoice.class, MigraxMetricsTest.SimpleRegistry.class)
          .addAsResource("db/migration/0001_notes.sql")
          .addAsResource("db/billing/0001_invoices.sql")
          .addAsResource("databases.properties", "application.properties"));

  @Inject
  AgroalDataSource main;

  @Inject
  @io.quarkus.agroal.DataSource("billing")
  AgroalDataSource billing;

  @Inject
  @org.eclipse.microprofile.health.Readiness
  MigraxReadinessCheck readiness;

  @Inject
  MeterRegistry registry;

  @Test
  void eachDatabaseGetsItsOwnMigrations() throws Exception {
    assertEquals(List.of("0001_notes.sql", "V0002__AddNote"), history(main));
    assertEquals(List.of("0001_invoices.sql", "V0002__SeedInvoice"), history(billing));
    assertEquals(List.of("billing_team", "from Java"),
        strings(billing, "SELECT owner FROM invoice ORDER BY id"));
  }

  @Test
  void readinessAndTheGaugeReportEachDatabase() {
    var response = readiness.call();
    assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.UP,
        response.getStatus());
    var data = response.getData().orElseThrow();
    assertEquals(2L, data.get("applied"));
    assertEquals(2L, data.get("billing.applied"));
    assertEquals(0L, data.get("billing.pending"));
    assertEquals(0.0, registry.get("migrax.migrations.pending").tag("database", "billing")
        .gauge().value());
    assertEquals(2.0, registry.get("migrax.migrations.applied").tag("database", "billing")
        .counter().count());
  }

  private static List<String> history(AgroalDataSource dataSource) throws Exception {
    return strings(dataSource, "SELECT version FROM migrax_history ORDER BY version");
  }

  private static List<String> strings(AgroalDataSource dataSource, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }
}
