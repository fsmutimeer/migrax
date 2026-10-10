package io.migrax.quarkus;

import io.migrax.quarkus.app.Note;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Starts a real Quarkus application: the migration must have run before the first query. */
@QuarkusTest
class MigraxStartupTest {
  @Inject
  EntityManager entityManager;

  @Inject
  @org.eclipse.microprofile.health.Readiness
  MigraxReadinessCheck readiness;

  @Test
  void reportsReadiness() {
    var response = readiness.call();
    assertEquals(org.eclipse.microprofile.health.HealthCheckResponse.Status.UP,
        response.getStatus());
    assertEquals(1L, response.getData().orElseThrow().get("applied"));
    assertEquals(0L, response.getData().orElseThrow().get("pending"));
  }

  @Test
  @Transactional
  void migrationsRunAtStartup() {
    Note note = entityManager.createQuery("from Note", Note.class).getSingleResult();
    assertEquals("hello from a placeholder", note.body);
    Number applied = (Number) entityManager
        .createNativeQuery("SELECT COUNT(*) FROM migrax_history").getSingleResult();
    assertEquals(1, applied.intValue());
  }
}
