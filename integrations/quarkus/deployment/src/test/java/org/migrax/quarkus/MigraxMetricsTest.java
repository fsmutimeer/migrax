package org.migrax.quarkus;

import db.migration.V0002__AddNote;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.migrax.api.MigrationListener;
import org.migrax.quarkus.app.Note;

import java.time.Duration;
import java.util.Map;

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

  // The application's own registry, not the JVM-wide composite: a composite answers from
  // whichever child it lists first, and the test applications of this module share one.
  @Inject
  SimpleMeterRegistry registry;

  @Test
  void theStartsMigrationsAreMeasured() {
    assertEquals(2.0, registry.get("migrax.migrations.applied").counter().count());
    assertEquals(1, registry.get("migrax.run").tag("outcome", "success").timer().count());
    assertEquals(0.0, registry.get("migrax.migrations.pending").gauge().value());
  }

  // Quarkus does not order the StartupEvent and the attaching of its registries; when the
  // event came first, the counters reached the registries at 0 (seen on CI).
  @Test
  void nothingIsLostWhenTheStartupEventComesBeforeTheRegistries() {
    CompositeMeterRegistry root = new CompositeMeterRegistry();
    SimpleMeterRegistry attached = new SimpleMeterRegistry();
    MigraxMetricsReplay replay = new MigraxMetricsReplay();
    MigrationListener recorded = replay.listener(Map.of(), "late");
    recorded.migrationApplied("0001_notes.sql", Duration.ZERO);
    recorded.migrationApplied("V0002__AddNote", Duration.ZERO);
    replay.onStart(null);
    root.add(attached);
    replay.bindTo(root);
    assertEquals(2.0, applied(attached));

    recorded.migrationApplied("0003_later.sql", Duration.ZERO);
    replay.onStart(null);
    assertEquals(3.0, applied(attached));
  }

  private static double applied(SimpleMeterRegistry registry) {
    return registry.get("migrax.migrations.applied").tag("database", "late").counter().count();
  }
}
