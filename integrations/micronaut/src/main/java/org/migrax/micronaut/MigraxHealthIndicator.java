package org.migrax.micronaut;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import org.migrax.runner.StartupMigrations;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.reactivestreams.Publisher;

/**
 * The {@code migrax} health indicator: UP when every migration of every database is applied and
 * none failed or changed, DOWN otherwise, with the counts as details ({@code pending}, ...;
 * {@code billing.pending}, ... for a further database). Active only when the application has
 * micronaut-management.
 */
@Singleton
@Requires(classes = HealthIndicator.class)
@Requires(property = "migrax.enabled", notEquals = "false")
public class MigraxHealthIndicator implements HealthIndicator {
  private final Environment environment;
  private final BeanContext beanContext;

  public MigraxHealthIndicator(Environment environment, BeanContext beanContext) {
    this.environment = environment;
    this.beanContext = beanContext;
  }

  @Override
  public Publisher<HealthResult> getResult() {
    Map<String, Object> details = new LinkedHashMap<>();
    boolean up = true;
    Exception failure = null;
    for (String database : MigraxDataSourceListener.databases(environment)) {
      String key = org.migrax.runner.Databases.DEFAULT.equals(database) ? "" : database + ".";
      try {
        StartupMigrations.Status status = status(database, environment, beanContext);
        details.put(key + "applied", status.applied());
        details.put(key + "pending", status.pending());
        details.put(key + "problems", status.problems());
        up &= status.upToDate();
      } catch (Exception e) {
        details.put(key + "error", String.valueOf(e.getMessage()));
        failure = failure == null ? e : failure;
        up = false;
      }
    }
    HealthResult.Builder result = HealthResult.builder("migrax",
        up ? HealthStatus.UP : HealthStatus.DOWN).details(details);
    return Publishers.just((failure == null ? result : result.exception(failure)).build());
  }

  /** The status of one database's data source. */
  static StartupMigrations.Status status(String database, Environment environment,
                                         BeanContext beanContext) throws Exception {
    DataSource dataSource = beanContext.getBean(DataSource.class,
        Qualifiers.byName(MigraxDataSourceListener.dataSourceName(database, environment)));
    return StartupMigrations.status(MigraxDataSourceListener.unwrap(dataSource),
        org.migrax.runner.Databases.settings(database,
            MigraxDataSourceListener.settings(environment)), environment.getClassLoader());
  }
}
