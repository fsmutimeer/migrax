package org.migrax.spring;

import org.migrax.spring.app.Book;
import org.migrax.spring.app.TestApp;
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

  /** A pipeline migrates: the application starts as it is, and reports what waits. */
  @Test
  void reportsWithoutMigratingWhenMigrateAtStartupIsFalse() {
    try (ConfigurableApplicationContext context = start("starter_report_only",
        "spring.jpa.hibernate.ddl-auto=none", "migrax.migrate-at-startup=false")) {
      JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
      assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
          + "WHERE TABLE_NAME = 'BOOK'", Integer.class));
      var health = context.getBean("migraxHealthIndicator",
          org.springframework.boot.actuate.health.HealthIndicator.class).health();
      assertEquals(org.springframework.boot.actuate.health.Status.DOWN, health.getStatus());
      assertEquals(3, health.getDetails().get("pending"));
      assertEquals(3.0, context.getBean(io.micrometer.core.instrument.MeterRegistry.class)
          .get("migrax.migrations.pending").gauge().value());
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

  /**
   * A second database: its own DataSource bean, folder (db/billing) and placeholders;
   * the default database and JPA keep the primary DataSource. Health and the pending gauge
   * report each database.
   */
  @Test
  void migratesEveryConfiguredDatabase() {
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class,
        TwoDatabases.class).properties(
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.main.web-application-type=none",
            "migrax.databases.billing.datasource=billingDataSource",
            "migrax.databases.billing.placeholders.owner=billing_team").run()) {
      JdbcTemplate main = new JdbcTemplate(context.getBean("dataSource",
          javax.sql.DataSource.class));
      JdbcTemplate billing = new JdbcTemplate(context.getBean("billingDataSource",
          javax.sql.DataSource.class));
      assertEquals(3, main.queryForObject("SELECT COUNT(*) FROM migrax_history", Integer.class));
      assertEquals(0, main.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
          + "WHERE TABLE_NAME = 'INVOICE'", Integer.class));
      assertEquals("billing_team",
          billing.queryForObject("SELECT owner FROM invoice", String.class));
      assertEquals(1, billing.queryForObject("SELECT COUNT(*) FROM migrax_history",
          Integer.class), "only the billing folder, not the default one or its Java beans");

      var health = context.getBean("migraxHealthIndicator",
          org.springframework.boot.actuate.health.CompositeHealthContributor.class);
      for (String database : java.util.List.of("default", "billing")) {
        var indicator = (org.springframework.boot.actuate.health.HealthIndicator)
            health.getContributor(database);
        assertEquals(org.springframework.boot.actuate.health.Status.UP,
            indicator.health().getStatus(), database);
      }
      var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
      assertEquals(1.0, registry.get("migrax.migrations.applied").tag("database", "billing")
          .counter().count());
      assertEquals(0.0, registry.get("migrax.migrations.pending").tag("database", "billing")
          .gauge().value());
    }
  }

  @Test
  void aMissingDataSourceBeanIsExplained() {
    Exception failure = assertThrows(Exception.class, () -> start("starter_missing_db",
        "migrax.databases.billing.locations=classpath:db/billing").close());
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    org.junit.jupiter.api.Assertions.assertTrue(cause.getMessage()
        .contains("no DataSource bean named 'billing'"), cause.getMessage());
  }

  /** Two DataSource beans, as an application with two databases defines them. */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class TwoDatabases {
    @org.springframework.context.annotation.Bean
    @org.springframework.context.annotation.Primary
    javax.sql.DataSource dataSource() {
      return h2("starter_two_main");
    }

    @org.springframework.context.annotation.Bean
    javax.sql.DataSource billingDataSource() {
      return h2("starter_two_billing");
    }

    private static javax.sql.DataSource h2(String name) {
      org.h2.jdbcx.JdbcDataSource dataSource = new org.h2.jdbcx.JdbcDataSource();
      dataSource.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
      dataSource.setUser("sa");
      return dataSource;
    }
  }

  /**
   * An OpenTelemetry that keeps its spans in memory, in place of Spring Boot's. Each test gets a
   * new exporter: closing a context shuts its exporter down.
   */
  @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
  static class InMemoryTracing {
    static io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter SPANS;

    @org.springframework.context.annotation.Bean
    io.opentelemetry.api.OpenTelemetry openTelemetry() {
      return io.opentelemetry.sdk.OpenTelemetrySdk.builder().setTracerProvider(
          io.opentelemetry.sdk.trace.SdkTracerProvider.builder().addSpanProcessor(
              io.opentelemetry.sdk.trace.export.SimpleSpanProcessor.create(SPANS)).build())
          .build();
    }
  }

  @Test
  void recordsMetricsAndSpansWhenTheApplicationHasThem() {
    InMemoryTracing.SPANS = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class,
        InMemoryTracing.class).properties(
            "spring.datasource.url=jdbc:h2:mem:starter_observe;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.main.web-application-type=none").run()) {
      var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
      assertEquals(3.0, registry.get("migrax.migrations.applied").counter().count());
      assertEquals(1, registry.get("migrax.run").tag("outcome", "success").timer().count());
      assertEquals(0.0, registry.get("migrax.migrations.pending").gauge().value());
      var spans = InMemoryTracing.SPANS.getFinishedSpanItems();
      assertEquals(1, spans.stream().filter(s -> s.getName().equals("migrax migrate")).count(),
          spans.toString());
      assertEquals(3, spans.stream().filter(s -> s.getName().equals("migrax migration"))
          .count(), spans.toString());
    }
  }

  @Test
  void metricsAndTracingCanBeTurnedOff() {
    InMemoryTracing.SPANS = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
    try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class,
        InMemoryTracing.class).properties(
            "spring.datasource.url=jdbc:h2:mem:starter_quiet;DB_CLOSE_DELAY=-1",
            "spring.jpa.hibernate.ddl-auto=validate",
            "spring.main.web-application-type=none",
            "migrax.metrics.enabled=false", "migrax.tracing.enabled=false").run()) {
      var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
      org.junit.jupiter.api.Assertions.assertNull(
          registry.find("migrax.migrations.applied").counter());
      org.junit.jupiter.api.Assertions.assertNull(
          registry.find("migrax.migrations.pending").gauge());
      assertEquals(0, InMemoryTracing.SPANS.getFinishedSpanItems().size());
    }
  }
}
