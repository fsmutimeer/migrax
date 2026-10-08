package io.migrax.micronaut;

import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.migrax.micronaut.app.Note;
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
