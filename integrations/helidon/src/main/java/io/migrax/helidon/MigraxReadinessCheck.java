package io.migrax.helidon;

import io.migrax.runner.StartupMigrations;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * The {@code migrax} readiness check (with helidon-microprofile-health): UP when every
 * migration is applied and none failed or changed, DOWN otherwise, with the counts as data.
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
      StartupMigrations.Status status = startup.status();
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
