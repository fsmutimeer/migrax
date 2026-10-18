package org.migrax.micronaut;

import io.micrometer.core.instrument.binder.MeterBinder;
import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.migrax.observe.PendingGauge;

/**
 * The gauge of pending migrations, only when the application has Micrometer and
 * {@code migrax.metrics.enabled} isn't false. Micronaut binds it to the application's
 * registries.
 */
@Factory
@Requires(classes = MeterBinder.class)
@Requires(property = "migrax.metrics.enabled", notEquals = "false")
public class MigraxMetrics {
  @Singleton
  @Named("migraxPendingGauge")
  MeterBinder migraxPendingGauge(Environment environment, BeanContext beanContext) {
    return registry -> MigraxDataSourceListener.databases(environment).forEach(database ->
        new PendingGauge(() -> MigraxHealthIndicator.status(database, environment, beanContext)
            .pending(), database).bindTo(registry));
  }
}
