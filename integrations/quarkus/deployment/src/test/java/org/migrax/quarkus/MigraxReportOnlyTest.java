package org.migrax.quarkus;

import db.migration.V0002__AddNote;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.migrax.quarkus.app.Note;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With migrax.migrate-at-startup=false a pipeline migrates: the application starts without
 * migrating, and readiness and the gauge report what waits.
 */
class MigraxReportOnlyTest {
  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar.addClasses(Note.class, V0002__AddNote.class,
              MigraxMetricsTest.SimpleRegistry.class)
          .addAsResource("db/migration/0001_notes.sql")
          .addAsResource("report-only.properties", "application.properties"));

  @Inject
  SimpleMeterRegistry registry;

  @Inject
  @org.eclipse.microprofile.health.Readiness
  MigraxReadinessCheck readiness;

  @Test
  void reportsWithoutMigrating() {
    var response = readiness.call();
    assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.DOWN,
        response.getStatus());
    assertEquals(2L, response.getData().orElseThrow().get("pending"));
    assertEquals(2.0, registry.get("migrax.migrations.pending").gauge().value());
  }
}
