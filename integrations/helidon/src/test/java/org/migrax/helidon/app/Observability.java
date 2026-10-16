package org.migrax.helidon.app;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/** A MeterRegistry and an OpenTelemetry that keeps its spans in memory. */
@ApplicationScoped
public class Observability {
  public static final SimpleMeterRegistry REGISTRY = new SimpleMeterRegistry();
  public static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

  @Produces
  @ApplicationScoped
  MeterRegistry registry() {
    return REGISTRY;
  }

  @Produces
  @ApplicationScoped
  OpenTelemetry openTelemetry() {
    return OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(SPANS)).build()).build();
  }
}
