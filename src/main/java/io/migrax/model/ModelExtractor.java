package io.migrax.model;

import io.migrax.dialect.Dialect;
import io.migrax.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the entity model of an application.
 *
 * <p>With Hibernate 6 on the application classpath, Migrax asks Hibernate itself for its mapping
 * (exact names, types, collection tables and inheritance). Otherwise, or when that fails in
 * {@link Mode#AUTO}, it scans JPA annotations with {@link JpaExtractor}.
 *
 * @since 0.1.0
 */
public final class ModelExtractor {
  private static final String ADAPTER_PACKAGE = "io.migrax.orm.";
  private static final String READER = "io.migrax.orm.HibernateSchemaReader";

  /** How entities are read. */
  public enum Mode {
    /** Hibernate metadata when Hibernate 6 is available, annotation scanning otherwise. */
    AUTO,
    /** Hibernate metadata only; fails if Hibernate 6 is unavailable. */
    HIBERNATE,
    /** JPA annotation scanning only. */
    ANNOTATIONS;

    public static Mode parse(String value) {
      if (value == null || value.isBlank()) {
        return AUTO;
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "auto" -> AUTO;
        case "hibernate" -> HIBERNATE;
        case "annotations", "annotation", "reflection" -> ANNOTATIONS;
        default -> throw new IllegalArgumentException(
            "Unknown extractor '" + value + "'. Use auto, hibernate or annotations.");
      };
    }
  }

  /** The model and a description of how it was read. */
  public record Result(SchemaModel model, String source) {}

  private ModelExtractor() {}

  /**
   * Reads the entities under {@code basePackage}.
   *
   * @param loader class loader with the application classes and dependencies
   * @param hibernateSettings Hibernate settings from the application configuration
   */
  public static Result extract(ClassLoader loader, String basePackage, NamingStrategy naming,
                               Dialect dialect, Map<String, String> hibernateSettings, Mode mode)
      throws Exception {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      if (mode != Mode.ANNOTATIONS) {
        String version = hibernateVersion(loader);
        boolean usable = readsMapping(version)
            && (mode == Mode.HIBERNATE || matchingEntities(loader, basePackage, version));
        if (usable) {
          try {
            return new Result(readWithHibernate(loader, basePackage, naming, dialect,
                hibernateSettings), "Hibernate " + version + " mapping");
          } catch (Exception | LinkageError e) {
            if (mode == Mode.HIBERNATE) {
              throw new IllegalStateException("Hibernate could not read the entity mapping: "
                  + rootMessage(e), e);
            }
            Log.warn("Hibernate could not read the entity mapping ({}); scanning JPA "
                + "annotations instead. Use --extractor hibernate to see the full error.",
                rootMessage(e));
          }
        } else if (mode == Mode.HIBERNATE) {
          throw new IllegalStateException("Hibernate 5.4 or newer is not on the project "
              + "classpath" + (version == null ? "" : " (found " + version + ")") + ".");
        }
      }
      return new Result(new JpaExtractor(naming).extract(basePackage), "JPA annotations");
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * True when Migrax can read this Hibernate version's own mapping: 5.4 and newer. Earlier
   * versions are read by scanning annotations.
   */
  public static boolean readsMapping(String version) {
    return version != null
        && (version.matches("^[6-9]\\..*") || version.matches("^5\\.[4-9]\\..*"));
  }

  /** The persistence API this Hibernate maps: javax for Hibernate 5, jakarta after. */
  public static String persistencePackage(String version) {
    return version != null && version.startsWith("5.") ? "javax.persistence"
        : "jakarta.persistence";
  }

  /** True when every entity uses the persistence API that the Hibernate version maps. */
  public static boolean matchingEntities(ClassLoader loader, String basePackage, String version)
      throws Exception {
    String entity = persistencePackage(version) + ".Entity";
    for (Class<?> type : JpaExtractor.scanEntities(loader, basePackage)) {
      boolean matches = false;
      for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
        matches |= annotation.annotationType().getName().equals(entity);
      }
      if (!matches) {
        return false;
      }
    }
    return true;
  }

  /** True when every entity uses jakarta.persistence, which Hibernate 6 requires. */
  public static boolean jakartaEntities(ClassLoader loader, String basePackage) throws Exception {
    return matchingEntities(loader, basePackage, "6");
  }

  /** Hibernate's version on the class loader, or null when Hibernate is absent. */
  public static String hibernateVersion(ClassLoader loader) {
    try {
      Class<?> version = Class.forName("org.hibernate.Version", true, loader);
      return String.valueOf(version.getMethod("getVersionString").invoke(null));
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private static SchemaModel readWithHibernate(ClassLoader loader, String basePackage,
                                               NamingStrategy naming, Dialect dialect,
                                               Map<String, String> settings) throws Exception {
    List<Class<?>> classes = new ArrayList<>(JpaExtractor.scanEntities(loader, basePackage));
    if (classes.isEmpty()) {
      return SchemaModel.empty();
    }
    String version = hibernateVersion(loader);
    if (!matchingEntities(loader, basePackage, version)) {
      // Hibernate 5 maps only javax.persistence entities, Hibernate 6+ only jakarta ones.
      throw new IllegalStateException("Not every entity uses " + persistencePackage(version)
          + ", which Hibernate " + version + " requires.");
    }
    classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Converter"));
    ClassLoader adapterLoader = new AdapterClassLoader(loader);
    Class<?> reader = adapterLoader.loadClass(READER);
    Object instance = reader.getConstructor().newInstance();
    try {
      return (SchemaModel) reader.getMethod("read", List.class, Map.class, NamingStrategy.class,
          Dialect.class).invoke(instance, classes, settings == null ? Map.of() : settings, naming,
          dialect);
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof Exception exception) {
        throw exception;
      }
      throw (Error) cause;
    }
  }

  /**
   * Starts the application's Hibernate against a database with schema validation.
   *
   * @return null when Hibernate accepts the schema, otherwise Hibernate's error
   * @throws IllegalStateException when Hibernate 6 is not on the class loader
   */
  public static String validateWithHibernate(ClassLoader loader, String basePackage,
                                             NamingStrategy naming, Dialect dialect,
                                             Map<String, String> settings, String url,
                                             String user, String password) throws Exception {
    String version = hibernateVersion(loader);
    if (!readsMapping(version)) {
      throw new IllegalStateException("Hibernate 5.4 or newer is not on the project classpath.");
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      List<Class<?>> classes = new ArrayList<>(JpaExtractor.scanEntities(loader, basePackage));
      classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Converter"));
      Class<?> validator = new AdapterClassLoader(loader)
          .loadClass("io.migrax.orm.HibernateSchemaValidator");
      Object instance = validator.getConstructor().newInstance();
      return (String) validator.getMethod("validate", List.class, Map.class,
          NamingStrategy.class, Dialect.class, String.class, String.class, String.class)
          .invoke(instance, classes, settings == null ? Map.of() : settings, naming, dialect,
              url, user, password);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  private static String rootMessage(Throwable error) {
    Throwable root = error;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String message = root.getMessage();
    return message == null ? root.getClass().getSimpleName() : message;
  }

  /**
   * Loads Migrax's Hibernate adapter classes from Migrax's own jar, but resolves everything
   * else (Hibernate, the entities) through the application's class loader.
   */
  private static final class AdapterClassLoader extends ClassLoader {
    AdapterClassLoader(ClassLoader parent) {
      super(parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith(ADAPTER_PACKAGE)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
          String resource = name.replace('.', '/') + ".class";
          try (InputStream in = ModelExtractor.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
              throw new ClassNotFoundException(name);
            }
            byte[] bytes = in.readAllBytes();
            loaded = defineClass(name, bytes, 0, bytes.length);
          } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
          }
        }
        if (resolve) {
          resolveClass(loaded);
        }
        return loaded;
      }
    }
  }
}
