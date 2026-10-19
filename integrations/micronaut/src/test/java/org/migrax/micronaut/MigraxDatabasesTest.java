package org.migrax.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.inject.qualifiers.Qualifiers;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A second database, the data source billing, nothing else uses: it is migrated at startup from
 * its own folder (db/billing) with its own placeholders; health and the pending gauge report it.
 */
class MigraxDatabasesTest {

  private static Map<String, Object> properties(String suffix) {
    return Map.of(
        "datasources.default.url", "jdbc:h2:mem:micronaut_two_main" + suffix + ";DB_CLOSE_DELAY=-1",
        "datasources.billing.url", "jdbc:h2:mem:micronaut_two_billing" + suffix
            + ";DB_CLOSE_DELAY=-1",
        "datasources.billing.driver-class-name", "org.h2.Driver",
        "datasources.billing.username", "sa",
        "datasources.billing.password", "",
        "migrax.databases.billing.placeholders.owner", "billing_team",
        // Its own entities, validated by Hibernate: it starts only if billing was migrated first.
        "jpa.billing.entity-scan.packages", "org.migrax.micronaut.billing",
        "jpa.billing.properties.hibernate.hbm2ddl.auto", "validate");
  }

  /** The tracing test of the other class counts spans in the same exporter. */
  @org.junit.jupiter.api.AfterEach
  void forgetSpans() {
    org.migrax.micronaut.app.InMemoryTracing.SPANS.reset();
  }

  @Test
  void migratesEveryConfiguredDatabase() throws Exception {
    try (ApplicationContext context = ApplicationContext.run(properties(""))) {
      DataSource billing = context.getBean(DataSource.class, Qualifiers.byName("billing"));
      assertEquals(List.of("billing_team"), strings(billing, "SELECT owner FROM invoice"));
      assertEquals(List.of("0001_invoices.sql"),
          strings(billing, "SELECT version FROM migrax_history"));
      DataSource main = context.getBean(DataSource.class, Qualifiers.byName("default"));
      assertEquals(List.of("0001_notes.sql"), strings(main, "SELECT version FROM migrax_history"));

      var registry = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
      assertEquals(1.0, registry.get("migrax.migrations.applied").tag("database", "billing")
          .counter().count());
      assertEquals(0.0, registry.get("migrax.migrations.pending").tag("database", "billing")
          .gauge().value());
      String details = String.valueOf(firstResult(context.getBean(MigraxHealthIndicator.class))
          .getDetails());
      assertEquals("{applied=1, pending=0, problems=0, billing.applied=1, billing.pending=0,"
          + " billing.problems=0}", details);
    }
  }

  @Test
  void aMissingDataSourceIsExplained() {
    Exception failure = assertThrows(Exception.class, () -> ApplicationContext.run(Map.of(
        "datasources.default.url", "jdbc:h2:mem:micronaut_missing;DB_CLOSE_DELAY=-1",
        "migrax.databases.audit.locations", "db/audit")).close());
    Throwable cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    assertTrue(cause.getMessage().contains("no data source named 'audit'"), cause.getMessage());
  }

  private static io.micronaut.management.health.indicator.HealthResult firstResult(
      MigraxHealthIndicator indicator) throws Exception {
    java.util.concurrent.CompletableFuture<io.micronaut.management.health.indicator.HealthResult>
        result = new java.util.concurrent.CompletableFuture<>();
    indicator.getResult().subscribe(new org.reactivestreams.Subscriber<>() {
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
    return result.get(30, java.util.concurrent.TimeUnit.SECONDS);
  }

  private static List<String> strings(DataSource dataSource, String sql) throws Exception {
    List<String> values = new ArrayList<>();
    try (Connection connection = MigraxDataSourceListener.unwrap(dataSource).getConnection();
         var statement = connection.createStatement();
         ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }
}
