package io.migrax.spring;

import io.migrax.spring.app.Book;
import io.migrax.spring.app.TestApp;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigraxAutoConfigurationTest {

  private static ConfigurableApplicationContext start(String database, String... extra) {
    String[] properties = new String[extra.length + 3];
    properties[0] = "spring.datasource.url=jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1";
    properties[1] = "spring.jpa.hibernate.ddl-auto=validate";
    properties[2] = "spring.main.web-application-type=none";
    System.arraycopy(extra, 0, properties, 3, extra.length);
    return new SpringApplicationBuilder(TestApp.class).properties(properties).run();
  }

  /** Hibernate validates at startup, so the context only starts if migrations ran first. */
  @Test
  void migratesBeforeHibernateValidatesTheSchema() {
    try (ConfigurableApplicationContext context = start("starter_ok")) {
      JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
      assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM migrax_history", Integer.class));
      assertEquals("Seeded by Java",
          jdbc.queryForObject("SELECT title FROM book_titles", String.class));

      var entityManager = context.getBean(EntityManagerFactory.class).createEntityManager();
      entityManager.getTransaction().begin();
      Book book = new Book();
      book.title = "Persisted by JPA";
      entityManager.persist(book);
      entityManager.getTransaction().commit();
      entityManager.close();
      assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM book", Integer.class));
    }
  }

  @Test
  void restartsWithoutReapplying() {
    try (ConfigurableApplicationContext ignored = start("starter_restart")) {
      // First start applies everything.
    }
    try (ConfigurableApplicationContext context = start("starter_restart")) {
      JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
      assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM book", Integer.class));
    }
  }

  @Test
  void reportsHealthWhenActuatorIsPresent() throws Exception {
    try (ConfigurableApplicationContext context = start("starter_health")) {
      var health = context.getBean("migraxHealthIndicator",
          org.springframework.boot.actuate.health.HealthIndicator.class).health();
      assertEquals(org.springframework.boot.actuate.health.Status.UP, health.getStatus());
      assertEquals(3, health.getDetails().get("applied"));
      assertEquals(0, health.getDetails().get("pending"));

      // A migration the database doesn't have yet (as when migrax.enabled=false).
      new JdbcTemplate(context.getBean(javax.sql.DataSource.class))
          .update("DELETE FROM migrax_history WHERE version = '0001_books.sql'");
      var behind = context.getBean("migraxHealthIndicator",
          org.springframework.boot.actuate.health.HealthIndicator.class).health();
      assertEquals(org.springframework.boot.actuate.health.Status.DOWN, behind.getStatus());
      assertEquals(1, behind.getDetails().get("pending"));
    }
  }

  @Test
  void healthIndicatorCanBeTurnedOff() {
    try (ConfigurableApplicationContext context = start("starter_no_health",
        "management.health.migrax.enabled=false")) {
      org.junit.jupiter.api.Assertions.assertFalse(
          context.containsBean("migraxHealthIndicator"));
    }
  }

  @Test
  void canBeDisabled() {
    assertThrows(Exception.class, () -> start("starter_disabled", "migrax.enabled=false").close());
  }
}
