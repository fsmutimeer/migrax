package org.migrax.spring;

import org.migrax.runner.StartupMigrations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * The {@code migrax} health component on Spring Boot 4, whose Actuator moved health indicators
 * to {@code org.springframework.boot.health}: the same as {@link MigraxHealthIndicator}.
 */
final class MigraxBoot4HealthIndicator implements HealthIndicator {
  private final MigraxMigrator migrator;

  MigraxBoot4HealthIndicator(MigraxMigrator migrator) {
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
