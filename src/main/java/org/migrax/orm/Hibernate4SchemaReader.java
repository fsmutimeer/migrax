package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.NamingStrategy;
import org.migrax.model.SchemaModel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.migrax.orm.HibernateMapping.call;
import static org.migrax.orm.HibernateMapping.constraintName;
import static org.migrax.orm.HibernateMapping.generatedName;
import static org.migrax.orm.HibernateMapping.list;
import static org.migrax.orm.HibernateMapping.logicalType;
import static org.migrax.orm.HibernateMapping.normalize;
import static org.migrax.orm.HibernateMapping.text;

/**
 * Reads the entity mapping of Hibernate 4.0 to 4.3, and validates a schema with it. These
 * versions build their mapping with {@code Configuration} instead of the {@code Metadata} API of
 * Hibernate 5 and newer, but describe tables with the same {@code org.hibernate.mapping}
 * classes. Every Hibernate call is reflective: Migrax compiles against a current Hibernate,
 * which no longer has these methods. Loaded in a child class loader of the application's class
 * loader, like {@link HibernateSchemaReader}.
 */
public final class Hibernate4SchemaReader {
  /** Hibernate 4's PostgreSQL dialects, newest first. */
  private static final List<String> POSTGRES = List.of("org.hibernate.dialect.PostgreSQL9Dialect",
      "org.hibernate.dialect.PostgreSQL82Dialect", "org.hibernate.dialect.PostgreSQLDialect");

  /** Hibernate 4 dialects per Migrax dialect id, newest first; the first one present is used. */
  private static final Map<String, List<String>> DIALECTS = Map.ofEntries(
      Map.entry("postgresql", POSTGRES),
      Map.entry("cockroachdb", POSTGRES),
      Map.entry("yugabytedb", POSTGRES),
      Map.entry("mysql", List.of("org.hibernate.dialect.MySQL5InnoDBDialect")),
      Map.entry("mariadb", List.of("org.hibernate.dialect.MySQL5InnoDBDialect")),
      Map.entry("tidb", List.of("org.hibernate.dialect.MySQL5InnoDBDialect")),
      Map.entry("sqlserver", List.of("org.hibernate.dialect.SQLServer2012Dialect",
          "org.hibernate.dialect.SQLServer2008Dialect")),
      Map.entry("oracle", List.of("org.hibernate.dialect.Oracle10gDialect")),
      Map.entry("h2", List.of("org.hibernate.dialect.H2Dialect")),
      Map.entry("hsqldb", List.of("org.hibernate.dialect.HSQLDialect")),
      Map.entry("derby", List.of("org.hibernate.dialect.DerbyTenSevenDialect")));

  /** A sequence in Hibernate's own DDL: name, then optional start and increment. */
  private static final Pattern CREATE_SEQUENCE = Pattern.compile("(?i)create sequence (\\S+)"
      + "(?:.*?\\bstart(?: with)? (\\d+))?(?:.*?\\bincrement(?: by)? (\\d+))?.*");

  /** Hibernate 4's hashed key names, such as FK_aarm0qd8tp13wq6hliekblik2. */
  private static final Pattern HASHED_NAME = Pattern.compile("(?i)(FK|UK)_[0-9a-z]{20,}");

  /** Hibernate's version string, e.g. 4.3.11.Final. */
  public String version() throws Exception {
    return (String) Class.forName("org.hibernate.Version", true, loader())
        .getMethod("getVersionString").invoke(null);
  }

  /**
   * Builds Hibernate's mapping for the given classes and converts it.
   *
   * @param classes entity and converter classes
   * @param settings Hibernate settings from the application configuration
   * @param naming naming strategy to use when the settings do not name one
   * @param dialect Migrax dialect of the target database
   */
  public SchemaModel read(List<Class<?>> classes, Map<String, String> settings,
                          NamingStrategy naming, Dialect dialect) throws Exception {
    Object configuration = configuration(classes, settings, naming, dialect);
    call(configuration, "buildMappings");
    Object mapping = call(configuration, "buildMapping");
    Object hibernateDialect = Class.forName((String) call(configuration, "getProperty",
        "hibernate.dialect"), true, loader()).getConstructor().newInstance();
    applyBeanValidation(configuration, hibernateDialect, settings);
    Set<Object> identity = identityColumns(configuration, hibernateDialect);

    List<SchemaModel.Table> tables = new ArrayList<>();
    for (Object table : list(call(configuration, "getTableMappings"))) {
      if (!Boolean.TRUE.equals(call(table, "isPhysicalTable"))
          || Boolean.TRUE.equals(call(table, "isAbstractUnionTable"))
          || call(table, "getSubselect") != null) {
        continue;
      }
      tables.add(table(table, mapping, hibernateDialect, dialect, identity));
    }
    tables.sort(java.util.Comparator.comparing(SchemaModel.Table::name));

    // Sequences come from the id generators; Hibernate 4 has no list of them, but writes them
    // in its own DDL.
    List<SchemaModel.Sequence> sequences = new ArrayList<>();
    for (String statement : (String[]) call(configuration, "generateSchemaCreationScript",
        hibernateDialect)) {
      Matcher sequence = CREATE_SEQUENCE.matcher(statement.strip());
      if (sequence.matches()) {
        sequences.add(new SchemaModel.Sequence(sequence.group(1),
            sequence.group(2) == null ? 1L : Long.parseLong(sequence.group(2)),
            sequence.group(3) == null ? 1L : Long.parseLong(sequence.group(3))));
      }
    }
    return new SchemaModel(tables, sequences);
  }

  /**
   * Checks the schema of a database against the mapping with Hibernate's SchemaValidator, which
   * fails on a missing table, column or sequence and on an incompatible column type.
   *
   * @return null when Hibernate accepts the schema, otherwise Hibernate's error
   */
  public String validate(List<Class<?>> classes, Map<String, String> settings,
                         NamingStrategy naming, Dialect dialect, String url, String user,
                         String password) throws Exception {
    Object configuration = configuration(classes, settings, naming, dialect);
    call(configuration, "setProperty", "hibernate.connection.url", url);
    if (user != null) {
      call(configuration, "setProperty", "hibernate.connection.username", user);
    }
    if (password != null) {
      call(configuration, "setProperty", "hibernate.connection.password", password);
    }
    try {
      Class<?> tool = Class.forName("org.hibernate.tool.hbm2ddl.SchemaValidator", true,
          loader());
      Object validator = tool.getConstructor(configuration.getClass())
          .newInstance(configuration);
      tool.getMethod("validate").invoke(validator);
      return null;
    } catch (java.lang.reflect.InvocationTargetException e) {
      Throwable cause = e.getCause();
      org.migrax.util.Log.debug("Hibernate schema validation failed: {}", cause);
      return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }
  }

  /** A Configuration with the classes, Migrax's dialect and naming defaults and user settings. */
  private static Object configuration(List<Class<?>> classes, Map<String, String> settings,
                                      NamingStrategy naming, Dialect dialect) throws Exception {
    ClassLoader loader = loader();
    Object configuration = Class.forName("org.hibernate.cfg.Configuration", true, loader)
        .getConstructor().newInstance();
    String hibernateDialect = settings.get("hibernate.dialect");
    if (hibernateDialect == null) {
      for (String candidate : DIALECTS.getOrDefault(dialect.id(), List.of())) {
        if (present(candidate)) {
          hibernateDialect = candidate;
          break;
        }
      }
    }
    if (hibernateDialect == null) {
      throw new IllegalStateException("Hibernate 4 has no dialect for " + dialect.id()
          + ". Set hibernate.dialect in the application's configuration.");
    }
    call(configuration, "setProperty", "hibernate.dialect", hibernateDialect);
    Object namingStrategy = namingStrategy(settings, naming);
    if (namingStrategy != null) {
      call(configuration, "setNamingStrategy", namingStrategy);
    }
    for (Map.Entry<String, String> setting : settings.entrySet()) {
      call(configuration, "setProperty", setting.getKey(), setting.getValue());
    }
    for (Class<?> type : classes) {
      call(configuration, "addAnnotatedClass", type);
    }
    return configuration;
  }

  /**
   * Hibernate 4's naming strategy: the one the settings name
   * ({@code hibernate.ejb.naming_strategy}, which Spring Boot 1 sets from
   * {@code spring.jpa.hibernate.naming-strategy}); for snake case
   * Spring Boot's SpringNamingStrategy when present, else Hibernate's ImprovedNamingStrategy;
   * null for Hibernate's default, which keeps the names as written.
   */
  private static Object namingStrategy(Map<String, String> settings, NamingStrategy naming)
      throws Exception {
    String name = settings.get("hibernate.ejb.naming_strategy");
    if (name == null && naming.snakeCase()) {
      String spring = "org.springframework.boot.orm.jpa.hibernate.SpringNamingStrategy";
      name = naming.springJoinTables() && present(spring) ? spring
          : "org.hibernate.cfg.ImprovedNamingStrategy";
    }
    return name == null ? null
        : Class.forName(name, true, loader()).getConstructor().newInstance();
  }

  /**
   * Hibernate 4 makes {@code @NotNull} columns NOT NULL and takes lengths from {@code @Size}
   * when it starts, if Bean Validation is present and the validation mode allows it. The
   * mapping is built without starting Hibernate, so this applies the same step.
   */
  private static void applyBeanValidation(Object configuration, Object hibernateDialect,
                                          Map<String, String> settings) {
    String mode = settings.getOrDefault("javax.persistence.validation.mode", "auto")
        .toLowerCase(java.util.Locale.ROOT);
    if (mode.contains("none") || !present("javax.validation.Validation")) {
      return;
    }
    try {
      Class<?> activator = Class.forName(
          "org.hibernate.cfg.beanvalidation.TypeSafeActivator", true, loader());
      List<Object> entities = list(call(configuration, "getClassMappings"));
      Properties properties = (Properties) call(configuration, "getProperties");
      for (java.lang.reflect.Method method : activator.getMethods()) {
        if (!method.getName().equals("applyRelationalConstraints")) {
          continue;
        }
        if (method.getParameterCount() == 3) {
          method.invoke(null, entities, properties, hibernateDialect);
        } else if (method.getParameterCount() == 4) {
          Object factory = Class.forName("javax.validation.Validation", true, loader())
              .getMethod("buildDefaultValidatorFactory").invoke(null);
          method.invoke(null, factory, entities, properties, hibernateDialect);
        }
        return;
      }
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      // No Bean Validation provider: Hibernate skips the step in "auto" mode too.
      org.migrax.util.Log.debug("Bean Validation constraints not applied: {}", e);
    }
  }

  /**
   * Id columns whose values the database generates: {@code GenerationType.IDENTITY}, and
   * {@code AUTO} where Hibernate 4 picks identity for the database ("native").
   */
  private static Set<Object> identityColumns(Object configuration, Object hibernateDialect) {
    Set<Object> columns = Collections.newSetFromMap(new IdentityHashMap<>());
    Object nativeGenerator = call(hibernateDialect, "getNativeIdentifierGeneratorClass");
    boolean nativeIdentity = nativeGenerator instanceof Class<?> c
        && c.getSimpleName().equals("IdentityGenerator");
    for (Object entity : list(call(configuration, "getClassMappings"))) {
      Object identifier = call(entity, "getIdentifier");
      Object strategy = identifier == null ? null
          : call(identifier, "getIdentifierGeneratorStrategy");
      if ("identity".equals(strategy) || nativeIdentity && "native".equals(strategy)) {
        columns.addAll(list(call(identifier, "getColumnIterator")));
      }
    }
    return columns;
  }

  private static SchemaModel.Table table(Object table, Object mapping, Object hibernateDialect,
                                         Dialect dialect, Set<Object> identity) {
    String name = (String) call(table, "getName");
    Set<String> uniqueColumns = new java.util.HashSet<>();
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (Object key : list(call(table, "getUniqueKeyIterator"))) {
      List<String> columns = names(call(key, "getColumns"));
      if (columns.size() == 1) {
        uniqueColumns.add(columns.get(0));
      } else {
        indexes.add(new SchemaModel.Index(readable((String) call(key, "getName"),
            "uk_" + name + "_" + String.join("_", columns)), columns, true));
      }
    }
    for (Object index : list(call(table, "getIndexIterator"))) {
      List<String> columns = names(call(index, "getColumnIterator"));
      indexes.add(new SchemaModel.Index(readable((String) call(index, "getName"),
          "idx_" + name + "_" + String.join("_", columns)), columns, false));
    }

    List<SchemaModel.Column> columns = new ArrayList<>();
    List<SchemaModel.Check> checks = new ArrayList<>();
    for (Object column : list(call(table, "getColumnIterator"))) {
      String columnName = (String) call(column, "getName");
      columns.add(column(column, mapping, hibernateDialect, dialect,
          Boolean.TRUE.equals(call(column, "isUnique")) || uniqueColumns.contains(columnName),
          identity.contains(column)));
      check(checks, name, text(call(column, "getCheckConstraint")));
    }
    columns.sort(java.util.Comparator.comparing(SchemaModel.Column::name));
    for (Object condition : list(call(table, "getCheckConstraintsIterator"))) {
      check(checks, name, text(condition));
    }

    SchemaModel.PrimaryKey primaryKey = null;
    Object key = call(table, "getPrimaryKey");
    List<String> keyColumns = key == null ? List.of() : names(call(key, "getColumns"));
    if (!keyColumns.isEmpty()) {
      String keyName = (String) call(key, "getName");
      primaryKey = new SchemaModel.PrimaryKey(keyColumns,
          keyName == null || generatedName(keyName)
              ? SchemaModel.primaryKeyConstraintName(name) : keyName);
    }

    List<SchemaModel.ForeignKey> foreignKeys = new ArrayList<>();
    for (Object foreignKey : list(call(table, "getForeignKeyIterator"))) {
      Object target = call(foreignKey, "getReferencedTable");
      if (target == null || Boolean.FALSE.equals(call(foreignKey, "isCreationEnabled"))) {
        continue;
      }
      String targetName = (String) call(target, "getName");
      List<String> own = names(call(foreignKey, "getColumns"));
      Object targetKey = call(target, "getPrimaryKey");
      List<String> referenced = Boolean.TRUE.equals(call(foreignKey, "isReferenceToPrimaryKey"))
          && targetKey != null ? names(call(targetKey, "getColumns"))
          : names(call(foreignKey, "getReferencedColumns"));
      foreignKeys.add(new SchemaModel.ForeignKey(readable(
          (String) call(foreignKey, "getName"),
          "fk_" + name + "_" + String.join("_", own) + "_" + targetName),
          own, targetName, referenced));
    }
    foreignKeys.sort(java.util.Comparator.comparing(SchemaModel.ForeignKey::name));
    return new SchemaModel.Table(name, columns, primaryKey, indexes, foreignKeys, checks,
        text(call(table, "getComment")));
  }

  /** A readable name for a key Hibernate named with a hash (FK_..., or FK... as Hibernate 5). */
  private static String readable(String name, String fallback) {
    return constraintName(name != null && HASHED_NAME.matcher(name).matches() ? null : name,
        fallback);
  }

  private static void check(List<SchemaModel.Check> checks, String table, String condition) {
    if (condition != null) {
      String expression = condition.strip();
      SchemaModel.Check check = new SchemaModel.Check(
          SchemaModel.checkConstraintName(table, expression), expression);
      if (checks.stream().noneMatch(c -> c.name().equals(check.name()))) {
        checks.add(check);
      }
    }
  }

  private static SchemaModel.Column column(Object column, Object mapping,
                                           Object hibernateDialect, Dialect dialect,
                                           boolean unique, boolean identity) {
    String name = (String) call(column, "getName");
    String hibernateType = (String) call(column, "getSqlType", hibernateDialect, mapping);
    String logical = logicalType((Integer) call(column, "getSqlTypeCode", mapping));
    Integer length = null;
    Integer precision = null;
    Integer scale = null;
    if ("varchar".equals(logical) || "nvarchar".equals(logical) || "varbinary".equals(logical)) {
      int size = (Integer) call(column, "getLength");
      length = size == 255 ? null : size;
    } else if ("decimal".equals(logical)) {
      precision = (Integer) call(column, "getPrecision");
      scale = (Integer) call(column, "getScale");
    }
    boolean nullable = Boolean.TRUE.equals(call(column, "isNullable"));
    String defaultValue = (String) call(column, "getDefaultValue");
    String comment = text(call(column, "getComment"));
    SchemaModel.Column neutral = new SchemaModel.Column(name, logical, nullable, length,
        precision, scale, defaultValue, unique, identity, null, logical, comment);
    // Keep Migrax's portable logical type when it renders exactly as Hibernate would; otherwise
    // record Hibernate's SQL type as an explicit type.
    String rendered = null;
    try {
      rendered = dialect.columnType(neutral);
    } catch (RuntimeException ignored) {
      // Unmapped logical type: fall through to Hibernate's type.
    }
    if (rendered != null && normalize(rendered).equals(normalize(hibernateType))) {
      return neutral;
    }
    return new SchemaModel.Column(name, hibernateType, nullable, length, precision, scale,
        defaultValue, unique, identity, null, logical, comment);
  }

  /** The names of a list or iterator of Hibernate columns. */
  private static List<String> names(Object columns) {
    return list(columns).stream().map(column -> (String) call(column, "getName")).toList();
  }

  private static boolean present(String className) {
    try {
      Class.forName(className, false, loader());
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  private static ClassLoader loader() {
    return Thread.currentThread().getContextClassLoader();
  }
}
