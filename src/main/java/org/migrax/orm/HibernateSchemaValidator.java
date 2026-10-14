package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.NamingStrategy;

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
    StandardServiceRegistry registry = registry(settings, naming, dialect, url, user, password);
    try {
      MetadataSources sources = new MetadataSources(registry);
      classes.forEach(sources::addAnnotatedClass);
      org.hibernate.boot.Metadata metadata = sources.buildMetadata();
      try (SessionFactory ignored = metadata.buildSessionFactory()) {
        return null;
      } catch (RuntimeException startup) {
        if (!fromJavassist(startup)) {
          throw startup;
        }
        // Hibernate 5.0 to 5.2 build lazy-loading proxies with a Javassist that can't define
        // classes on current Java. The schema check doesn't need them: Hibernate's
        // SchemaValidator checks it from the mapping alone, with a new registry because the
        // failed start closed this one.
        StandardServiceRegistry fresh = registry(settings, naming, dialect, url, user, password);
        try {
          MetadataSources again = new MetadataSources(fresh);
          classes.forEach(again::addAnnotatedClass);
          validateWithTool(again.buildMetadata(), fresh);
        } finally {
          StandardServiceRegistryBuilder.destroy(fresh);
        }
        return null;
      }
    } catch (RuntimeException e) {
      java.io.StringWriter trace = new java.io.StringWriter();
      e.printStackTrace(new java.io.PrintWriter(trace));
      org.migrax.util.Log.debug("Hibernate schema validation failed:\n{}", trace);
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

  /** A service registry for validating against the given database. */
  private static StandardServiceRegistry registry(Map<String, String> settings,
                                                  NamingStrategy naming, Dialect dialect,
                                                  String url, String user, String password) {
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
    // Validation only reads the schema: no helper tables for bulk updates (Hibernate creates
    // HTE_ tables at startup on CockroachDB).
    builder.applySetting("hibernate.query.mutation_strategy.persistent.create_tables", "false");
    builder.applySetting("hibernate.hql.bulk_id_strategy.persistent.create_tables", "false");
    builder.applySetting("hibernate.boot.allow_jdbc_metadata_access", "true");
    builder.applySetting("jakarta.persistence.validation.mode", "none");
    builder.applySetting("javax.persistence.validation.mode", "none");
    return builder.build();
  }

  /** Whether a startup failure came from Javassist's proxy generation. */
  private static boolean fromJavassist(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      for (StackTraceElement frame : cause.getStackTrace()) {
        if (frame.getClassName().startsWith("javassist.")) {
          return true;
        }
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  /**
   * Hibernate's SchemaValidator tool, which reads the schema without starting a session
   * factory: {@code validate(Metadata, ServiceRegistry)} from 5.1, a constructor and
   * {@code validate()} in 5.0. Throws Hibernate's SchemaManagementException on a mismatch.
   */
  private static void validateWithTool(org.hibernate.boot.Metadata metadata,
                                       org.hibernate.service.ServiceRegistry registry) {
    try {
      Class<?> tool = Class.forName("org.hibernate.tool.hbm2ddl.SchemaValidator", true,
          metadata.getClass().getClassLoader());
      try {
        tool.getMethod("validate", org.hibernate.boot.Metadata.class,
            org.hibernate.service.ServiceRegistry.class)
            .invoke(tool.getConstructor().newInstance(), metadata, registry);
      } catch (NoSuchMethodException hibernate50) {
        Object validator;
        try {
          validator = findConstructor(tool, 2).newInstance(registry, metadata);
        } catch (NoSuchMethodException withoutRegistry) {
          validator = findConstructor(tool, 1).newInstance(metadata);
        }
        tool.getMethod("validate").invoke(validator);
      }
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause() instanceof RuntimeException runtime ? runtime
          : new IllegalStateException(e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("Hibernate's SchemaValidator is not available", e);
    }
  }

  private static java.lang.reflect.Constructor<?> findConstructor(Class<?> type, int parameters)
      throws NoSuchMethodException {
    for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
      if (constructor.getParameterCount() == parameters) {
        return constructor;
      }
    }
    throw new NoSuchMethodException(type.getName() + " with " + parameters + " parameters");
  }
}
