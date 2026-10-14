package org.migrax.quarkus;

import db.migration.V0002__AddNote;
import org.migrax.quarkus.app.Note;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Starts a real Quarkus application with Hibernate's schema validation on: it starts only if
 * the migrations ran before Hibernate.
 */
class MigraxStartupTest {
  @RegisterExtension
  static final QuarkusUnitTest APP = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar.addClasses(Note.class, V0002__AddNote.class)
          .addAsResource("db/migration/0001_notes.sql")
          .addAsResource("application.properties"));

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
    assertEquals(2L, response.getData().orElseThrow().get("applied"));
    assertEquals(0L, response.getData().orElseThrow().get("pending"));
  }

  @Test
  @Transactional
  void migrationsRunBeforeHibernateStarts() {
    List<String> bodies = entityManager.createQuery("select n.body from Note n order by n.id",
        String.class).getResultList();
    assertEquals(List.of("hello from a placeholder", "from Java"), bodies);
    Number applied = (Number) entityManager
        .createNativeQuery("SELECT COUNT(*) FROM migrax_history").getSingleResult();
    assertEquals(2, applied.intValue());
  }

  @Test
  void theMigrationsAreListedAtBuildTime() throws Exception {
    try (var in = Thread.currentThread().getContextClassLoader()
        .getResourceAsStream("META-INF/migrax/migrations.idx")) {
      String index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(index.contains("sql db/migration/0001_notes.sql\n"), index);
      assertTrue(index.contains("java db.migration.V0002__AddNote\n"), index);
    }
  }
}
