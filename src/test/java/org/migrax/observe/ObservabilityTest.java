package org.migrax.observe;

import org.migrax.api.MigrationListener;
import org.migrax.api.Migrax;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Metrics and spans of migration runs, with Micrometer's and OpenTelemetry's own test kits. */
class ObservabilityTest {
  @RegisterExtension
  static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

  @TempDir
  Path folder;

  private JdbcDataSource database(String name) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:observe_" + name + ";DB_CLOSE_DELAY=-1");
    return dataSource;
  }

  @Test
  void aRunIsMeasuredAndTraced() throws Exception {
    Files.writeString(folder.resolve("0001_note.sql"), "CREATE TABLE note (id INT);\n");
    Files.writeString(folder.resolve("0002_tag.sql"), "CREATE TABLE tag (id INT);\n");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Migrax migrax = Migrax.configure().dataSource(database("ok"))
        .locations("filesystem:" + folder)
        .listener(new MicrometerListener(registry),
            new OpenTelemetryListener(OTEL.getOpenTelemetry()))
        .load();
    assertEquals(2, migrax.migrate().applied());

    assertEquals(2.0, registry.get("migrax.migrations.applied").counter().count());
    assertEquals(1, registry.get("migrax.migration").tag("migration", "0001_note.sql")
        .tag("outcome", "success").timer().count());
    assertEquals(1, registry.get("migrax.run").tag("outcome", "success").timer().count());
    assertEquals(1, registry.get("migrax.lock.wait").timer().count());

    List<SpanData> spans = OTEL.getSpans();
    assertEquals(3, spans.size(), spans.toString());
    SpanData run = spans.stream().filter(s -> s.getName().equals("migrax migrate"))
        .findFirst().orElseThrow();
    assertEquals(2L, run.getAttributes().get(AttributeKey.longKey("migrax.applied")));
    List<SpanData> migrations = spans.stream()
        .filter(s -> s.getName().equals("migrax migration")).toList();
    assertEquals(List.of("0001_note.sql", "0002_tag.sql"), migrations.stream()
        .map(s -> s.getAttributes().get(AttributeKey.stringKey("migrax.migration"))).toList());
    assertTrue(migrations.stream().allMatch(s -> s.getParentSpanId().equals(run.getSpanId())));
  }

  @Test
  void aFailureIsCountedAndMarkedInTheTrace() throws Exception {
    Files.writeString(folder.resolve("0001_note.sql"), "CREATE TABLE note (id INT);\n");
    Files.writeString(folder.resolve("0002_broken.sql"), "CREATE TABLE note (id INT);\n");
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Migrax migrax = Migrax.configure().dataSource(database("broken"))
        .locations("filesystem:" + folder)
        .listener(new MicrometerListener(registry),
            new OpenTelemetryListener(OTEL.getOpenTelemetry()))
        .load();
    assertThrows(Exception.class, migrax::migrate);

    assertEquals(1.0, registry.get("migrax.migrations.failed").counter().count());
    assertEquals(1, registry.get("migrax.migration").tag("migration", "0002_broken.sql")
        .tag("outcome", "failure").timer().count());
    assertEquals(1, registry.get("migrax.run").tag("outcome", "failure").timer().count());
    SpanData failed = OTEL.getSpans().stream().filter(s -> "0002_broken.sql".equals(
        s.getAttributes().get(AttributeKey.stringKey("migrax.migration")))).findFirst()
        .orElseThrow();
    assertEquals(StatusCode.ERROR, failed.getStatus().getStatusCode());
    assertEquals(1, failed.getEvents().size(), "the exception is recorded");
  }

  @Test
  void aBrokenListenerNeverStopsAMigration() throws Exception {
    Files.writeString(folder.resolve("0001_note.sql"), "CREATE TABLE note (id INT);\n");
    MigrationListener broken = new MigrationListener() {
      @Override
      public void migrationApplied(String version, Duration duration) {
        throw new IllegalStateException("metrics backend down");
      }
    };
    assertEquals(1, Migrax.configure().dataSource(database("listener"))
        .locations("filesystem:" + folder).listener(broken).load().migrate().applied());
  }
}
