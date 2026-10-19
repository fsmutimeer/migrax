package org.migrax.spring;

import org.migrax.api.JavaMigration;

import java.util.List;
import javax.sql.DataSource;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.orm.jpa.EntityManagerFactoryDependsOnPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Runs Migrax migrations at startup, after the DataSource exists and before JPA starts.
 * Disable with {@code migrax.enabled=false}.
 */
@AutoConfiguration(
    afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
    beforeName = "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration")
@ConditionalOnClass(DataSource.class)
@ConditionalOnBean(DataSource.class)
@ConditionalOnProperty(prefix = "migrax", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(MigraxProperties.class)
public class MigraxAutoConfiguration {

  /** Bean name that the EntityManagerFactory waits for. */
  public static final String MIGRATOR_BEAN = "migraxMigrator";

  @Bean(name = MIGRATOR_BEAN)
  @ConditionalOnMissingBean(MigraxMigrator.class)
  public MigraxMigrator migraxMigrator(DataSource dataSource, MigraxProperties properties,
                                       ResourcePatternResolver resources,
                                       ObjectProvider<JavaMigration> javaMigrations,
                                       Environment environment, BeanFactory beanFactory) {
    List<String> packages = AutoConfigurationPackages.has(beanFactory)
        ? AutoConfigurationPackages.get(beanFactory) : List.of();
    MigraxMigrator migrator = new MigraxMigrator(dataSource, properties, resources,
        javaMigrations.orderedStream().toList(), environment, packages);
    if (beanFactory instanceof org.springframework.beans.factory.ListableBeanFactory beans) {
      migrator.setBeanFactory(beans);
    }
    // Further databases (migrax.databases.<name>), each from its own DataSource bean.
    for (var entry : properties.getDatabases().entrySet()) {
      String name = org.migrax.runner.Databases.checkName(entry.getKey());
      String bean = entry.getValue().getDatasource() != null
          ? entry.getValue().getDatasource() : name;
      if (!beanFactory.containsBean(bean)) {
        throw new IllegalStateException("migrax.databases." + name + ": no DataSource bean"
            + " named '" + bean + "'. Set migrax.databases." + name + ".datasource to the bean's"
            + " name.");
      }
      MigraxMigrator other = new MigraxMigrator(beanFactory.getBean(bean, DataSource.class),
          properties.forDatabase(name), resources, List.of(), environment, List.of(), name);
      if (beanFactory instanceof org.springframework.beans.factory.ListableBeanFactory beans) {
        other.setBeanFactory(beans);
      }
      migrator.addOther(other);
    }
    return migrator;
  }

  /**
   * The migrax health component, only when the application has Actuator: one indicator, or,
   * with further databases, one entry per database (default, billing, ...).
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
  static class MigraxHealth {
    @Bean
    @ConditionalOnMissingBean(name = "migraxHealthIndicator")
    @org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator(
        "migrax")
    org.springframework.boot.actuate.health.HealthContributor migraxHealthIndicator(
        MigraxMigrator migrator) {
      if (migrator.others().isEmpty()) {
        return new MigraxHealthIndicator(migrator);
      }
      java.util.Map<String, org.springframework.boot.actuate.health.HealthIndicator> byDatabase =
          new java.util.LinkedHashMap<>();
      byDatabase.put(migrator.database(), new MigraxHealthIndicator(migrator));
      migrator.others().forEach(m -> byDatabase.put(m.database(), new MigraxHealthIndicator(m)));
      return org.springframework.boot.actuate.health.CompositeHealthContributor
          .fromMap(byDatabase);
    }
  }

  /**
   * The gauge of pending migrations, only when the application has Micrometer; Spring Boot binds
   * it to the application's registries.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "io.micrometer.core.instrument.binder.MeterBinder")
  @ConditionalOnProperty(prefix = "migrax.metrics", name = "enabled", matchIfMissing = true)
  static class MigraxMetrics {
    @Bean
    @ConditionalOnMissingBean(name = "migraxPendingGauge")
    io.micrometer.core.instrument.binder.MeterBinder migraxPendingGauge(
        MigraxMigrator migrator) {
      List<MigraxMigrator> all = new java.util.ArrayList<>(List.of(migrator));
      all.addAll(migrator.others());
      return registry -> all.forEach(m -> new org.migrax.observe.PendingGauge(
          () -> m.status().pending(), m.database()).bindTo(registry));
    }
  }

  /** Makes JPA wait for migrations, so Hibernate validation sees the migrated schema. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.orm.jpa.AbstractEntityManagerFactoryBean")
  static class JpaDependsOnMigrax {
    @Bean
    static EntityManagerFactoryDependsOnPostProcessor migraxEntityManagerFactoryDependsOn() {
      return new EntityManagerFactoryDependsOnPostProcessor(MIGRATOR_BEAN);
    }
  }
}
