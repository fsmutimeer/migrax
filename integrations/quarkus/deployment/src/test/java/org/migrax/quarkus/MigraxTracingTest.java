package org.migrax.quarkus;

import db.migration.V0002__AddNote;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.migrax.quarkus.app.Note;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** With quarkus-opentelemetry, the migrations of the start are spans. */
class MigraxTracingTest {
  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar.addClasses(Note.class, V0002__AddNote.class,
              Exporter.class)
          .addAsResource("db/migration/0001_notes.sql")
          // A database of its own: the other tests' applications migrated theirs already.
          .addAsResource("tracing.properties", "application.properties"))
      .overrideConfigKey("quarkus.otel.traces.exporter", "cdi")
      .overrideConfigKey("quarkus.otel.bsp.schedule.delay", "50ms");

  /** Keeps the spans in memory. */
  @jakarta.inject.Singleton
  public static class Exporter {
    @jakarta.enterprise.inject.Produces
    @jakarta.inject.Singleton
    InMemorySpanExporter exporter() {
      return InMemorySpanExporter.create();
    }
  }

  @Inject
  InMemorySpanExporter spans;

  @Test
  void theStartsMigrationsAreSpans() throws Exception {
    long migrations = 0;
    for (int i = 0; i < 100 && migrations < 2; i++) {
      migrations = spans.getFinishedSpanItems().stream()
          .filter(s -> s.getName().equals("migrax migration")).count();
      Thread.sleep(100);
    }
    assertEquals(2, migrations, spans.getFinishedSpanItems().toString());
    assertEquals(1, spans.getFinishedSpanItems().stream()
        .filter(s -> s.getName().equals("migrax migrate")).count());
  }
}
