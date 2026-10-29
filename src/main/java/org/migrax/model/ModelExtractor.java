package org.migrax.model;

import org.migrax.dialect.Dialect;
import org.migrax.util.Log;

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
  private static final String ADAPTER_PACKAGE = "org.migrax.orm.";
  private static final String READER = "org.migrax.orm.HibernateSchemaReader";
  /** Hibernate 4 builds its mapping with Configuration, not Metadata. */
  private static final String HIBERNATE4_READER = "org.migrax.orm.Hibernate4SchemaReader";
  private static final String ECLIPSELINK_READER = "org.migrax.orm.EclipseLinkSchemaReader";
  private static final String OPENJPA_READER = "org.migrax.orm.OpenJpaSchemaReader";
  private static final String SPRING_DATA_JDBC_READER =
      "org.migrax.orm.SpringDataJdbcSchemaReader";
  private static final String MICRONAUT_DATA_READER =
      "org.migrax.orm.MicronautDataSchemaReader";
  /** Annotation packages of other stores: their repositories' types are not JDBC aggregates. */
  private static final List<String> OTHER_STORES = List.of("jakarta.persistence.",
      "javax.persistence.", "org.springframework.data.mongodb.",
      "org.springframework.data.redis.", "org.springframework.data.neo4j.",
      "org.springframework.data.cassandra.", "org.springframework.data.elasticsearch.",
      "org.springframework.data.couchbase.");

  /** How entities are read. */
  public enum Mode {
    /**
     * Hibernate's mapping when Hibernate is available, else EclipseLink's or OpenJPA's when the
     * project has one of them, else annotation scanning.
     */
    AUTO,
    /** Hibernate's mapping only; fails if Hibernate 4 or newer is unavailable. */
    HIBERNATE,
    /** EclipseLink's mapping only; fails if EclipseLink is unavailable (since 0.6.0). */
    ECLIPSELINK,
    /** OpenJPA's mapping only; fails if OpenJPA is unavailable (since 0.6.0). */
    OPENJPA,
    /**
     * Spring Data JDBC's mapping only; fails if Spring Data JDBC is unavailable (since 0.17.0).
     */
    SPRING_DATA_JDBC,
    /**
     * Micronaut Data's mapping only; fails if Micronaut Data is unavailable (since 0.17.0).
     */
    MICRONAUT_DATA,
    /** JPA annotation scanning only. */
    ANNOTATIONS;

    public static Mode parse(String value) {
      if (value == null || value.isBlank()) {
        return AUTO;
      }
      return switch (value.trim().toLowerCase(Locale.ROOT)) {
        case "auto" -> AUTO;
        case "hibernate" -> HIBERNATE;
        case "eclipselink" -> ECLIPSELINK;
        case "openjpa" -> OPENJPA;
        case "spring-data-jdbc" -> SPRING_DATA_JDBC;
        case "micronaut-data" -> MICRONAUT_DATA;
        case "annotations", "annotation", "reflection" -> ANNOTATIONS;
        default -> throw new IllegalArgumentException(
            "Unknown extractor '" + value + "'. Use auto, hibernate, eclipselink, openjpa, "
                + "spring-data-jdbc, micronaut-data or annotations.");
      };
    }
  }

  /**
   * The model, a description of how it was read, and the entity class that owns each table
   * (table name to class name; empty when the reader does not tell).
   */
  public record Result(SchemaModel model, String source, Map<String, String> entities) {
    public Result {
      entities = entities == null ? Map.of() : Map.copyOf(entities);
    }

    public Result(SchemaModel model, String source) {
      this(model, source, Map.of());
    }
  }

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
      if (readsSpringDataJdbc(loader, basePackage, mode)) {
        return readSpringDataJdbc(loader, basePackage, dialect, hibernateSettings);
      }
      if (readsMicronautData(loader, basePackage, mode)) {
        return readMicronautData(loader, basePackage, dialect, hibernateSettings);
      }
      // Without Hibernate: the project's EclipseLink, else its OpenJPA.
      boolean otherProvider = mode == Mode.AUTO && !readsMapping(hibernateVersion(loader));
      Mode provider = mode == Mode.ECLIPSELINK || mode == Mode.OPENJPA ? mode
          : otherProvider && eclipseLinkVersion(loader) != null ? Mode.ECLIPSELINK
          : otherProvider && openJpaVersion(loader) != null ? Mode.OPENJPA : null;
      if (provider != null) {
        boolean eclipseLink = provider == Mode.ECLIPSELINK;
        String product = eclipseLink ? "EclipseLink" : "OpenJPA";
        String version = eclipseLink ? eclipseLinkVersion(loader) : openJpaVersion(loader);
        if (version == null) {
          throw new IllegalStateException(product + " is not on the project classpath.");
        }
        try {
          return new Result(readWithProvider(eclipseLink ? ECLIPSELINK_READER : OPENJPA_READER,
              loader, basePackage, dialect, hibernateSettings),
              product + " " + version + " mapping");
        } catch (Exception | LinkageError e) {
          if (mode == provider) {
            throw new IllegalStateException(product + " could not read the entity mapping: "
                + rootMessage(e), e);
          }
          Log.warn(product + " could not read the entity mapping ({}); scanning JPA "
              + "annotations instead. Use --extractor " + product.toLowerCase(Locale.ROOT)
              + " to see the full error.", rootMessage(e));
        }
      } else if (mode != Mode.ANNOTATIONS) {
        String version = hibernateVersion(loader);
        boolean usable = readsMapping(version)
            && (mode == Mode.HIBERNATE || matchingEntities(loader, basePackage, version));
        if (usable) {
          try {
            return readWithHibernate(loader, basePackage, naming, dialect, hibernateSettings,
                "Hibernate " + version + " mapping");
          } catch (Exception | LinkageError e) {
            if (mode == Mode.HIBERNATE) {
              throw new IllegalStateException("Hibernate could not read the entity mapping: "
                  + explain(e, version), e);
            }
            Log.warn("Hibernate could not read the entity mapping ({}); scanning JPA "
                + "annotations instead. Use --extractor hibernate to see the full error.",
                explain(e, version));
          }
        } else if (mode == Mode.HIBERNATE) {
          throw new IllegalStateException("Hibernate 4 or newer is not on the project "
              + "classpath" + (version == null ? "" : " (found " + version + ")") + ".");
        }
      }
      boolean enumChecks = hibernateSettings != null && "true".equalsIgnoreCase(
          hibernateSettings.get("migrax.enum-checks"));
      JpaExtractor.Extraction scanned =
          new JpaExtractor(naming, enumChecks).extractWithEntities(basePackage);
      return new Result(scanned.model(), "JPA annotations", scanned.entities());
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * True when Migrax can read this Hibernate version's own mapping: 4.0 and newer. Earlier
   * versions are read by scanning annotations.
   */
  public static boolean readsMapping(String version) {
    return version != null && (version.matches("^[6-9]\\..*")
        || version.matches("^5\\.\\d+\\..*") || version.matches("^4\\.\\d+\\..*"));
  }

  /** The persistence API this Hibernate maps: javax up to Hibernate 5, jakarta after. */
  public static String persistencePackage(String version) {
    return version != null && (version.startsWith("5.") || version.startsWith("4."))
        ? "javax.persistence" : "jakarta.persistence";
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

  /**
   * EclipseLink's version on the class loader, or null when EclipseLink is absent.
   *
   * @since 0.6.0
   */
  public static String eclipseLinkVersion(ClassLoader loader) {
    try {
      Class<?> version = Class.forName("org.eclipse.persistence.Version", true, loader);
      return String.valueOf(version.getMethod("getVersion").invoke(null));
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  /**
   * OpenJPA's version on the class loader, or null when OpenJPA is absent.
   *
   * @since 0.6.0
   */
  public static String openJpaVersion(ClassLoader loader) {
    try {
      Class<?> version = Class.forName("org.apache.openjpa.conf.OpenJPAVersion", true, loader);
      return String.valueOf(version.getField("VERSION_NUMBER").get(null));
    } catch (ReflectiveOperationException | LinkageError e) {
      return null;
    }
  }

  /** Reads the entities with an adapter for the project's EclipseLink or OpenJPA. */
  private static SchemaModel readWithProvider(String adapter, ClassLoader loader,
                                              String basePackage, Dialect dialect,
                                              Map<String, String> settings) throws Exception {
    List<Class<?>> classes = new ArrayList<>(JpaExtractor.scanEntities(loader, basePackage));
    classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Embeddable"));
    classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "MappedSuperclass"));
    classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Converter"));
    return readWithAdapter(adapter, loader, classes, dialect, settings);
  }

  /**
   * Whether the entities are read through Spring Data JDBC: with {@link Mode#SPRING_DATA_JDBC},
   * or in {@link Mode#AUTO} when Spring Data JDBC is on the classpath and the package has
   * aggregates but no JPA entities.
   *
   * @since 0.17.0
   */
  public static boolean readsSpringDataJdbc(ClassLoader loader, String basePackage, Mode mode)
      throws Exception {
    return mode == Mode.SPRING_DATA_JDBC || mode == Mode.AUTO
        && springDataJdbcVersion(loader) != null
        && JpaExtractor.scanEntities(loader, basePackage).isEmpty()
        && !springDataJdbcAggregates(loader, basePackage).isEmpty();
  }

  /**
   * Whether the entities are read through Micronaut Data: with {@link Mode#MICRONAUT_DATA}, or
   * in {@link Mode#AUTO} when Micronaut Data is on the classpath and the package has
   * {@code @MappedEntity} classes but no JPA entities.
   *
   * @since 0.17.0
   */
  public static boolean readsMicronautData(ClassLoader loader, String basePackage, Mode mode)
      throws Exception {
    return mode == Mode.MICRONAUT_DATA || mode == Mode.AUTO
        && micronautDataVersion(loader) != null
        && JpaExtractor.scanEntities(loader, basePackage).isEmpty()
        && !JpaExtractor.scanAnnotated(loader, basePackage, "MappedEntity").isEmpty();
  }

  /**
   * Micronaut Data's version on the class loader ("" when its jar doesn't say), or null when it
   * is absent.
   *
   * @since 0.17.0
   */
  public static String micronautDataVersion(ClassLoader loader) {
    try {
      Class<?> entity = Class.forName(
          "io.micronaut.data.model.runtime.RuntimePersistentEntity", false, loader);
      String version = entity.getPackage().getImplementationVersion();
      return version == null ? "" : version;
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  /** Reads Micronaut Data's {@code @MappedEntity} classes. */
  private static Result readMicronautData(ClassLoader loader, String basePackage,
                                          Dialect dialect, Map<String, String> settings)
      throws Exception {
    String version = micronautDataVersion(loader);
    if (version == null) {
      throw new IllegalStateException("Micronaut Data is not on the project classpath.");
    }
    List<Class<?>> entities = JpaExtractor.scanAnnotated(loader, basePackage, "MappedEntity");
    try {
      return new Result(readWithAdapter(MICRONAUT_DATA_READER, loader, entities, dialect,
          settings), ("Micronaut Data " + version).trim() + " mapping");
    } catch (Exception | LinkageError e) {
      String message = rootMessage(e);
      if (message.contains("introspection")) {
        message += ". Micronaut Data reads its mapping from what its annotation processor "
            + "compiled: build the project with micronaut-data-processor";
      }
      throw new IllegalStateException("Micronaut Data could not read the entity mapping: "
          + message, e);
    }
  }

  /** Reads Spring Data JDBC's aggregates. */
  private static Result readSpringDataJdbc(ClassLoader loader, String basePackage,
                                           Dialect dialect, Map<String, String> settings)
      throws Exception {
    String version = springDataJdbcVersion(loader);
    if (version == null) {
      throw new IllegalStateException("Spring Data JDBC is not on the project classpath.");
    }
    List<Class<?>> roots = springDataJdbcAggregates(loader, basePackage);
    try {
      return new Result(readWithAdapter(SPRING_DATA_JDBC_READER, loader, roots, dialect,
          settings), ("Spring Data JDBC " + version).trim() + " mapping");
    } catch (Exception | LinkageError e) {
      throw new IllegalStateException("Spring Data JDBC could not read the entity mapping: "
          + rootMessage(e), e);
    }
  }

  /**
   * Spring Data JDBC's version on the class loader ("" when its jar doesn't say), or null when
   * it is absent.
   *
   * @since 0.17.0
   */
  public static String springDataJdbcVersion(ClassLoader loader) {
    try {
      Class<?> context = Class.forName(
          "org.springframework.data.jdbc.core.mapping.JdbcMappingContext", false, loader);
      String version = context.getPackage().getImplementationVersion();
      return version == null ? "" : version;
    } catch (ClassNotFoundException | LinkageError e) {
      return null;
    }
  }

  /**
   * The aggregate roots under {@code basePackage}: classes with Spring Data's relational
   * {@code @Table}, and the types of repository interfaces (Spring Data JDBC needs no
   * annotation). Types annotated for another store (JPA, MongoDB, ...) are left out.
   */
  @SuppressWarnings("unchecked")
  private static List<Class<?>> springDataJdbcAggregates(ClassLoader loader, String basePackage)
      throws Exception {
    Class<? extends java.lang.annotation.Annotation> table =
        (Class<? extends java.lang.annotation.Annotation>) Class.forName(
            "org.springframework.data.relational.core.mapping.Table", false, loader);
    Class<?> repository = Class.forName("org.springframework.data.repository.Repository",
        false, loader);
    java.util.Set<Class<?>> roots = new java.util.TreeSet<>(
        java.util.Comparator.comparing(Class::getName));
    for (Class<?> type : org.migrax.util.ClassScanner.scan(loader, basePackage,
        c -> c.isAnnotationPresent(table) || c.isInterface() && repository.isAssignableFrom(c))) {
      Class<?> root = type.isInterface() ? domainType(type, repository) : type;
      if (root != null && !root.isInterface() && !otherStore(root)) {
        roots.add(root);
      }
    }
    return new ArrayList<>(roots);
  }

  /** The aggregate type {@code T} of a repository interface, or null when it is generic. */
  private static Class<?> domainType(Class<?> type, Class<?> repository) {
    for (java.lang.reflect.Type parent : type.getGenericInterfaces()) {
      if (parent instanceof java.lang.reflect.ParameterizedType generic
          && generic.getRawType() instanceof Class<?> raw && repository.isAssignableFrom(raw)
          && generic.getActualTypeArguments().length > 0) {
        return generic.getActualTypeArguments()[0] instanceof Class<?> domain ? domain : null;
      }
      if (parent instanceof Class<?> raw && repository.isAssignableFrom(raw)) {
        Class<?> domain = domainType(raw, repository);
        if (domain != null) {
          return domain;
        }
      }
    }
    return null;
  }

  private static boolean otherStore(Class<?> type) {
    for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
      String name = annotation.annotationType().getName();
      if (OTHER_STORES.stream().anyMatch(name::startsWith)) {
        return true;
      }
    }
    return false;
  }

  /** Runs a Migrax adapter, loaded against the application's library, on the classes. */
  private static SchemaModel readWithAdapter(String adapter, ClassLoader loader,
                                             List<Class<?>> classes, Dialect dialect,
                                             Map<String, String> settings) throws Exception {
    if (classes.isEmpty()) {
      return SchemaModel.empty();
    }
    Class<?> reader = new AdapterClassLoader(loader).loadClass(adapter);
    try {
      return (SchemaModel) reader.getMethod("read", List.class, Map.class, Dialect.class)
          .invoke(reader.getConstructor().newInstance(), classes,
              settings == null ? Map.of() : settings, dialect);
    } catch (java.lang.reflect.InvocationTargetException e) {
      throw e.getCause() instanceof Exception cause ? cause : e;
    }
  }

  /** The reader's table-to-entity map; empty when the reader has none (Hibernate 4). */
  @SuppressWarnings("unchecked")
  private static Map<String, String> entities(Class<?> reader, Object instance)
      throws ReflectiveOperationException {
    java.lang.reflect.Method method;
    try {
      method = reader.getMethod("entities");
    } catch (NoSuchMethodException e) {
      return Map.of();
    }
    return (Map<String, String>) method.invoke(instance);
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
  private static Result readWithHibernate(ClassLoader loader, String basePackage,
                                          NamingStrategy naming, Dialect dialect,
                                          Map<String, String> settings, String source)
      throws Exception {
    List<Class<?>> classes = new ArrayList<>(JpaExtractor.scanEntities(loader, basePackage));
    if (classes.isEmpty()) {
      return new Result(SchemaModel.empty(), source);
    }
    String version = hibernateVersion(loader);
    if (!matchingEntities(loader, basePackage, version)) {
      // Hibernate 4 and 5 map only javax.persistence entities, Hibernate 6+ only jakarta ones.
      throw new IllegalStateException("Not every entity uses " + persistencePackage(version)
          + ", which Hibernate " + version + " requires.");
    }
    classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Converter"));
    ClassLoader adapterLoader = new AdapterClassLoader(loader);
    Class<?> reader = adapterLoader.loadClass(version.startsWith("4.") ? HIBERNATE4_READER
        : READER);
    Object instance = reader.getConstructor().newInstance();
    try {
      SchemaModel model = (SchemaModel) reader.getMethod("read", List.class, Map.class,
          NamingStrategy.class, Dialect.class).invoke(instance, classes,
          settings == null ? Map.of() : settings, naming, dialect);
      return new Result(model, source, entities(reader, instance));
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
      throw new IllegalStateException("Hibernate 4 or newer is not on the project classpath.");
    }
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      List<Class<?>> classes = new ArrayList<>(JpaExtractor.scanEntities(loader, basePackage));
      classes.addAll(JpaExtractor.scanAnnotated(loader, basePackage, "Converter"));
      Class<?> validator = new AdapterClassLoader(loader).loadClass(version.startsWith("4.")
          ? HIBERNATE4_READER : "org.migrax.orm.HibernateSchemaValidator");
      Object instance = validator.getConstructor().newInstance();
      return (String) validator.getMethod("validate", List.class, Map.class,
          NamingStrategy.class, Dialect.class, String.class, String.class, String.class)
          .invoke(instance, classes, settings == null ? Map.of() : settings, naming, dialect,
              url, user, password);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /**
   * Whether Hibernate failed because a class it needs is missing from the project, such as
   * JAXB for Hibernate 5.0 to 5.2 on Java 11 and newer.
   *
   * @since 0.6.0
   */
  public static boolean missingClass(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }

  /**
   * Hibernate's error in a sentence, with what to do when JAXB is missing.
   *
   * @since 0.6.0
   */
  public static String explain(Throwable error, String version) {
    String message = rootMessage(error);
    return message.contains("javax.xml.bind") || message.contains("javax/xml/bind")
        ? message + ". Hibernate " + version + " needs JAXB, which Java 11 and newer don't "
            + "include: add javax.xml.bind:jaxb-api and org.glassfish.jaxb:jaxb-runtime to the "
            + "project"
        : message;
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
