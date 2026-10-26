package org.migrax.observe;

import org.migrax.api.MigrationListener;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Keeps the events of migration runs to tell another listener later, for metrics backends
 * that are not ready yet when the migrations run (Quarkus may attach its registries after
 * the migrations ran). Durations are kept as measured.
 *
 * @since 0.13.0
 */
public final class RecordingListener implements MigrationListener {
  private final List<Consumer<MigrationListener>> events = new ArrayList<>();

  @Override
  public synchronized void runStarted() {
    events.add(MigrationListener::runStarted);
  }

  @Override
  public synchronized void lockAcquired(Duration waited) {
    events.add(listener -> listener.lockAcquired(waited));
  }

  @Override
  public synchronized void migrationStarted(String version) {
    events.add(listener -> listener.migrationStarted(version));
  }

  @Override
  public synchronized void migrationApplied(String version, Duration duration) {
    events.add(listener -> listener.migrationApplied(version, duration));
  }

  @Override
  public synchronized void migrationFailed(String version, Duration duration, Throwable error) {
    events.add(listener -> listener.migrationFailed(version, duration, error));
  }

  @Override
  public synchronized void runFinished(int applied, Duration duration, Throwable error) {
    events.add(listener -> listener.runFinished(applied, duration, error));
  }

  /** Tells {@code listener} everything recorded so far, in order, and forgets it. */
  public synchronized void replayTo(MigrationListener listener) {
    events.forEach(event -> event.accept(listener));
    events.clear();
  }
}
