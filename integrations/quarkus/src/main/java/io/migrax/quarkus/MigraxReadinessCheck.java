package io.migrax.quarkus;

import io.migrax.runner.StartupMigrations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The {@code migrax} readiness check (with quarkus-smallrye-health): UP when every migration is
 * applied and none failed or changed, DOWN otherwise, with the counts as data.
 */
@Readiness
@ApplicationScoped
public class MigraxReadinessCheck implements HealthCheck {
  @Inject
  Instance<DataSource> dataSource;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response = HealthCheckResponse.named("migrax");
    if (!dataSource.isResolvable()) {
      return response.down().withData("reason", "no default DataSource").build();
    }
    try {
      StartupMigrations.Status status = StartupMigrations.status(dataSource.get(),
          MigraxStartup.settings(ConfigProvider.getConfig()),
          Thread.currentThread().getContextClassLoader());
      return response.status(status.upToDate())
          .withData("applied", status.applied())
          .withData("pending", status.pending())
          .withData("problems", status.problems())
          .build();
    } catch (Exception e) {
      return response.down().withData("error", String.valueOf(e.getMessage())).build();
    }
  }
}
