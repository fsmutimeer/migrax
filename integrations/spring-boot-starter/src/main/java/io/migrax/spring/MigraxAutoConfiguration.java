package io.migrax.spring;

import io.migrax.api.JavaMigration;

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
    return new MigraxMigrator(dataSource, properties, resources,
        javaMigrations.orderedStream().toList(), environment, packages);
  }

  /** The migrax health indicator, only when the application has Actuator. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
  static class MigraxHealth {
    @Bean
    @ConditionalOnMissingBean(name = "migraxHealthIndicator")
    @org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator(
        "migrax")
    MigraxHealthIndicator migraxHealthIndicator(MigraxMigrator migrator) {
      return new MigraxHealthIndicator(migrator);
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
