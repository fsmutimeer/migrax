package io.migrax.spring;

import io.migrax.runner.StartupMigrations;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

/**
 * The {@code migrax} health component: UP when every migration is applied and none failed or
 * changed, DOWN otherwise, with the counts as details. Registered only when the application
 * has Spring Boot Actuator.
 */
public class MigraxHealthIndicator implements HealthIndicator {
  private final MigraxMigrator migrator;

  public MigraxHealthIndicator(MigraxMigrator migrator) {
    this.migrator = migrator;
  }

  @Override
  public Health health() {
    try {
      StartupMigrations.Status status = migrator.status();
      return (status.upToDate() ? Health.up() : Health.down())
          .withDetail("applied", status.applied())
          .withDetail("pending", status.pending())
          .withDetail("problems", status.problems())
          .build();
    } catch (Exception e) {
      return Health.down(e).build();
    }
  }
}
