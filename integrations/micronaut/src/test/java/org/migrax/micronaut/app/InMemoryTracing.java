package org.migrax.micronaut.app;

import io.micronaut.context.annotation.Factory;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import jakarta.inject.Singleton;

/** An OpenTelemetry that keeps its spans in memory. */
@Factory
public class InMemoryTracing {
  public static final InMemorySpanExporter SPANS = InMemorySpanExporter.create();

  @Singleton
  OpenTelemetry openTelemetry() {
    return OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(SPANS)).build()).build();
  }
}
