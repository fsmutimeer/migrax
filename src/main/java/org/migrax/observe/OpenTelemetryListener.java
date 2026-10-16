package org.migrax.observe;

import org.migrax.api.MigrationListener;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;

import java.time.Duration;

/**
 * Records OpenTelemetry spans: {@code migrax migrate} for each run (a child of the current
 * span, when there is one), with one {@code migrax migration} span per
 * migration under it. The migration's name is the {@code migrax.migration} attribute; a failure
 * marks the spans as errors with the exception. Needs the OpenTelemetry API on the class path;
 * the framework integrations add it when the application has an {@code OpenTelemetry}.
 *
 * @since 0.13.0
 */
public final class OpenTelemetryListener implements MigrationListener {
  private final Tracer tracer;
  /** Spans of the run and of the migration in progress, per thread: runs may overlap. */
  private final ThreadLocal<Span> run = new ThreadLocal<>();
  private final ThreadLocal<Span> migration = new ThreadLocal<>();

  public OpenTelemetryListener(OpenTelemetry openTelemetry) {
    this.tracer = openTelemetry.getTracer("org.migrax");
  }

  @Override
  public void runStarted() {
    run.set(tracer.spanBuilder("migrax migrate").startSpan());
  }

  @Override
  public void lockAcquired(Duration waited) {
    Span span = run.get();
    if (span != null) {
      span.setAttribute("migrax.lock.wait_ms", waited.toMillis());
    }
  }

  @Override
  public void migrationStarted(String version) {
    Span parent = run.get();
    migration.set(tracer.spanBuilder("migrax migration")
        .setParent(parent == null ? Context.current() : Context.current().with(parent))
        .setAttribute("migrax.migration", version)
        .startSpan());
  }

  @Override
  public void migrationApplied(String version, Duration duration) {
    Span span = migration.get();
    if (span != null) {
      span.end();
      migration.remove();
    }
  }

  @Override
  public void migrationFailed(String version, Duration duration, Throwable error) {
    Span span = migration.get();
    if (span != null) {
      span.recordException(error);
      span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
      span.end();
      migration.remove();
    }
  }

  @Override
  public void runFinished(int applied, Duration duration, Throwable error) {
    Span span = run.get();
    if (span == null) {
      return;
    }
    span.setAttribute("migrax.applied", applied);
    if (error != null) {
      span.recordException(error);
      span.setStatus(StatusCode.ERROR, String.valueOf(error.getMessage()));
    }
    span.end();
    run.remove();
  }
}
