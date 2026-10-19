package org.migrax.helidon;

import org.migrax.runner.StartupMigrations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The {@code migrax} readiness check (with helidon-microprofile-health): UP when every
 * migration of every database is applied and none failed or changed, DOWN otherwise, with the
 * counts as data ({@code pending}, ...; {@code billing.pending}, ... for a further database).
 */
@Readiness
@ApplicationScoped
public class MigraxReadinessCheck implements HealthCheck {
  @Inject
  MigraxStartup startup;

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder response = HealthCheckResponse.named("migrax");
    try {
      boolean up = true;
      for (var entry : startup.statuses().entrySet()) {
        String key = org.migrax.runner.Databases.DEFAULT.equals(entry.getKey()) ? ""
            : entry.getKey() + ".";
        StartupMigrations.Status status = entry.getValue();
        response.withData(key + "applied", status.applied())
            .withData(key + "pending", status.pending())
            .withData(key + "problems", status.problems());
        up &= status.upToDate();
      }
      return response.status(up).build();
    } catch (Exception e) {
      return response.down().withData("error", String.valueOf(e.getMessage())).build();
    }
  }
}
