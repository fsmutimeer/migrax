package org.migrax.micronaut;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.migrax.micronaut.app.Note;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Hibernate validates the schema when Micronaut starts (hbm2ddl.auto=validate) against an
 * empty database, so this only starts if Migrax migrated the DataSource first.
 */
@MicronautTest(transactional = false)
class MigraxDataSourceListenerTest {
  @Inject
  EntityManagerFactory entityManagerFactory;

  @Inject
  DataSource dataSource;

  @Inject
  MigraxHealthIndicator health;

  @Inject
  io.micrometer.core.instrument.MeterRegistry registry;

  @Test
  void recordsMetricsAndSpans() {
    assertEquals(1.0, registry.get("migrax.migrations.applied").counter().count());
    assertEquals(0.0, registry.get("migrax.migrations.pending").gauge().value());
    var spans = org.migrax.micronaut.app.InMemoryTracing.SPANS.getFinishedSpanItems();
    assertEquals(1, spans.stream().filter(s -> s.getName().equals("migrax migrate")).count(),
        spans.toString());
  }

  @Test
  void reportsHealth() throws Exception {
    java.util.concurrent.CompletableFuture<io.micronaut.management.health.indicator.HealthResult>
        result = new java.util.concurrent.CompletableFuture<>();
    health.getResult().subscribe(new org.reactivestreams.Subscriber<>() {
      @Override
      public void onSubscribe(org.reactivestreams.Subscription subscription) {
        subscription.request(1);
      }

      @Override
      public void onNext(io.micronaut.management.health.indicator.HealthResult item) {
        result.complete(item);
      }

      @Override
      public void onError(Throwable error) {
        result.completeExceptionally(error);
      }

      @Override
      public void onComplete() {
      }
    });
    var health = result.get(30, java.util.concurrent.TimeUnit.SECONDS);
    assertEquals(io.micronaut.health.HealthStatus.UP, health.getStatus());
    assertEquals("{applied=1, pending=0, problems=0}", String.valueOf(health.getDetails()));
  }

  @Test
  void migratesBeforeHibernateValidatesTheSchema() throws Exception {
    EntityManager em = entityManagerFactory.createEntityManager();
    em.getTransaction().begin();
    Note note = new Note();
    note.noteText = "hello";
    em.persist(note);
    em.getTransaction().commit();
    assertEquals(1L, em.createQuery("select count(n) from Note n", Long.class)
        .getSingleResult());
    em.close();

    try (Connection connection = io.micronaut.data.connection.jdbc.advice.DelegatingDataSource
        .unwrapDataSource(dataSource).getConnection();
         ResultSet rows = connection.createStatement()
             .executeQuery("SELECT version FROM migrax_history")) {
      rows.next();
      assertEquals("0001_notes.sql", rows.getString(1));
    }
  }
}
