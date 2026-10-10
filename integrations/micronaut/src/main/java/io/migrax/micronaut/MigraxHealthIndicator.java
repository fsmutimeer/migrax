package io.migrax.micronaut;

import io.micronaut.context.BeanContext;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.async.publisher.Publishers;
import io.micronaut.health.HealthStatus;
import io.micronaut.inject.qualifiers.Qualifiers;
import io.micronaut.management.health.indicator.HealthIndicator;
import io.micronaut.management.health.indicator.HealthResult;
import io.migrax.runner.StartupMigrations;
import jakarta.inject.Singleton;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.reactivestreams.Publisher;

/**
 * The {@code migrax} health indicator: UP when every migration is applied and none failed or
 * changed, DOWN otherwise, with the counts as details. Active only when the application has
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
    HealthResult result;
    try {
      String name = environment.getProperty("migrax.datasource", String.class)
          .orElse("default");
      DataSource dataSource = beanContext.getBean(DataSource.class, Qualifiers.byName(name));
      StartupMigrations.Status status = StartupMigrations.status(
          MigraxDataSourceListener.unwrap(dataSource),
          MigraxDataSourceListener.settings(environment), environment.getClassLoader());
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("applied", status.applied());
      details.put("pending", status.pending());
      details.put("problems", status.problems());
      result = HealthResult.builder("migrax", status.upToDate() ? HealthStatus.UP
          : HealthStatus.DOWN).details(details).build();
    } catch (Exception e) {
      result = HealthResult.builder("migrax", HealthStatus.DOWN).exception(e).build();
    }
    return Publishers.just(result);
  }
}
