package org.migrax.quarkus;

import db.migration.V0002__AddNote;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.migrax.quarkus.app.Note;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With quarkus-micrometer, the migrations of the start are in its registry. */
class MigraxMetricsTest {
  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar.addClasses(Note.class, V0002__AddNote.class,
              SimpleRegistry.class)
          .addAsResource("db/migration/0001_notes.sql")
          // A database of its own: the other tests' applications migrated theirs already.
          .addAsResource("metrics.properties", "application.properties"));

  /** A registry of the application's own, which Quarkus attaches after startup. */
  @jakarta.inject.Singleton
  public static class SimpleRegistry {
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Singleton
    io.micrometer.core.instrument.simple.SimpleMeterRegistry registry() {
      return new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    }
  }

  @Inject
  MeterRegistry registry;

  @Test
  void theStartsMigrationsAreMeasured() {
    assertEquals(2.0, registry.get("migrax.migrations.applied").counter().count());
    assertEquals(1, registry.get("migrax.run").tag("outcome", "success").timer().count());
    assertEquals(0.0, registry.get("migrax.migrations.pending").gauge().value());
  }
}
