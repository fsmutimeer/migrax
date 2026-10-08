package io.migrax.orm;

import io.migrax.dialect.Dialect;
import io.migrax.model.NamingStrategy;

import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

/**
 * Starts Hibernate with {@code hbm2ddl.auto=validate} against a database, which fails on any
 * missing table, column or sequence and on incompatible column types. Loaded in a child class
 * loader of the application's class loader, like {@link HibernateSchemaReader}.
 */
public final class HibernateSchemaValidator {

  /** Returns null when Hibernate accepts the schema, or Hibernate's error message. */
  public String validate(List<Class<?>> classes, Map<String, String> settings,
                         NamingStrategy naming, Dialect dialect, String url, String user,
                         String password) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    StandardServiceRegistryBuilder builder =
        HibernateSchemaReader.registryBuilder(loader, settings, naming, dialect);
    builder.applySetting("hibernate.connection.url", url);
    if (user != null) {
      builder.applySetting("hibernate.connection.username", user);
    }
    if (password != null) {
      builder.applySetting("hibernate.connection.password", password);
    }
    builder.applySetting("hibernate.hbm2ddl.auto", "validate");
    builder.applySetting("hibernate.boot.allow_jdbc_metadata_access", "true");
    builder.applySetting("jakarta.persistence.validation.mode", "none");
    builder.applySetting("javax.persistence.validation.mode", "none");
    StandardServiceRegistry registry = builder.build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      classes.forEach(sources::addAnnotatedClass);
      try (SessionFactory ignored = sources.buildMetadata().buildSessionFactory()) {
        return null;
      }
    } catch (RuntimeException e) {
      Throwable cause = e;
      while (cause.getCause() != null && cause.getCause() != cause
          && !(cause instanceof org.hibernate.tool.schema.spi.SchemaManagementException)) {
        cause = cause.getCause();
      }
      return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }
}
