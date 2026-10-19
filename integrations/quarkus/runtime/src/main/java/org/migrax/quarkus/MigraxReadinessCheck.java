package org.migrax.quarkus;

import org.migrax.runner.Databases;
import org.migrax.runner.StartupMigrations;
import jakarta.enterprise.context.ApplicationScoped;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The {@code migrax} readiness check (with quarkus-smallrye-health): UP when every migration of
 * every database is applied and none failed or changed, DOWN otherwise, with the counts as data
 * ({@code pending}, ...; {@code billing.pending}, ... for a further database).
 */
@Readiness
@ApplicationScoped
public class MigraxReadinessCheck implements HealthCheck {

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response = HealthCheckResponse.named("migrax");
    Config config = ConfigProvider.getConfig();
    boolean up = true;
    for (String database : MigraxStartup.databases(config)) {
      String key = Databases.DEFAULT.equals(database) ? "" : database + ".";
      DataSource dataSource = MigraxStartup.dataSource(database, config);
      if (dataSource == null) {
        response.withData(key + "reason", Databases.DEFAULT.equals(database)
            ? "no default DataSource" : "no data source");
        up = false;
        continue;
      }
      try {
        StartupMigrations.Status status = StartupMigrations.status(dataSource,
            Databases.settings(database, MigraxStartup.settings(config)),
            Thread.currentThread().getContextClassLoader());
        response.withData(key + "applied", status.applied())
            .withData(key + "pending", status.pending())
            .withData(key + "problems", status.problems());
        up &= status.upToDate();
      } catch (Exception e) {
        response.withData(key + "error", String.valueOf(e.getMessage()));
        up = false;
      }
    }
    return response.status(up).build();
  }
}
