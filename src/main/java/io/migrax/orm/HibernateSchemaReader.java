package io.migrax.orm;

import io.migrax.dialect.Dialect;
import io.migrax.model.NamingStrategy;
import io.migrax.model.SchemaModel;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.relational.Namespace;
import org.hibernate.boot.model.relational.Sequence;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Column;
import org.hibernate.mapping.Component;
import org.hibernate.mapping.ForeignKey;
import org.hibernate.mapping.Index;
import org.hibernate.mapping.PersistentClass;
import org.hibernate.mapping.Property;
import org.hibernate.mapping.Selectable;
import org.hibernate.mapping.SingleTableSubclass;
import org.hibernate.mapping.Table;
import org.hibernate.mapping.UniqueKey;

/**
 * Reads the schema from Hibernate 6's own mapping model, so names, types, join tables,
 * collection tables, inheritance and sequences are exactly what Hibernate expects. Hibernate is
 * booted without a database connection.
 *
 * <p>This class is loaded in a child class loader of the application's class loader, because
 * Hibernate is an application dependency rather than a Migrax dependency.
 */
public final class HibernateSchemaReader {
  private static final Pattern GENERATED_NAME = Pattern.compile("(?i)(FK|UK|IDX)[0-9a-z]{15,}");

  /** Hibernate dialect class per Migrax dialect id. */
  private static final Map<String, String> DIALECTS = Map.of(
      "postgresql", "org.hibernate.dialect.PostgreSQLDialect",
      "mysql", "org.hibernate.dialect.MySQLDialect",
      "mariadb", "org.hibernate.dialect.MariaDBDialect",
      "sqlserver", "org.hibernate.dialect.SQLServerDialect",
      "oracle", "org.hibernate.dialect.OracleDialect",
      "h2", "org.hibernate.dialect.H2Dialect",
      "cockroachdb", "org.hibernate.dialect.CockroachDialect");

  /**
   * Hibernate 5 dialects, as its resolver picks them for current database versions; its
   * unversioned MySQLDialect and PostgreSQLDialect describe very old servers.
   */
  private static final Map<String, String> HIBERNATE5_DIALECTS = Map.of(
      "postgresql", "org.hibernate.dialect.PostgreSQL10Dialect",
      "mysql", "org.hibernate.dialect.MySQL8Dialect",
      "mariadb", "org.hibernate.dialect.MariaDB103Dialect",
      "sqlserver", "org.hibernate.dialect.SQLServer2012Dialect",
      "oracle", "org.hibernate.dialect.Oracle12cDialect",
      "h2", "org.hibernate.dialect.H2Dialect",
      "cockroachdb", "org.hibernate.dialect.CockroachDB201Dialect");

  private static final String CAMEL_CASE_TO_UNDERSCORES =
      "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy";

  /** Hibernate's version string, e.g. 6.6.13.Final. */
  public String version() {
    return org.hibernate.Version.getVersionString();
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
                          NamingStrategy naming, Dialect dialect) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    StandardServiceRegistryBuilder builder = registryBuilder(loader, settings, naming, dialect);
    builder.applySetting("hibernate.boot.allow_jdbc_metadata_access", "false");
    builder.applySetting("hibernate.temp.use_jdbc_metadata_defaults", "false");
    StandardServiceRegistry registry = builder.build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      classes.forEach(sources::addAnnotatedClass);
      Metadata metadata = sources.buildMetadata();
      if (beanValidationApplies(loader, settings)) {
        applyBeanValidation(metadata);
      }
      return convert(metadata, dialect);
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  /** Service registry builder with Migrax's dialect and naming defaults and user settings. */
  static StandardServiceRegistryBuilder registryBuilder(ClassLoader loader,
                                                        Map<String, String> settings,
                                                        NamingStrategy naming, Dialect dialect) {
    BootstrapServiceRegistry bootstrap = new BootstrapServiceRegistryBuilder()
        .applyClassLoader(loader)
        .build();
    StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder(bootstrap);
    Map<String, Object> config = new LinkedHashMap<>();
    boolean hibernate5 = org.hibernate.Version.getVersionString().startsWith("5.");
    config.put("hibernate.dialect",
        (hibernate5 ? HIBERNATE5_DIALECTS : DIALECTS).get(dialect.id()));
    if (naming.micronaut()) {
      config.put("hibernate.physical_naming_strategy", micronautPhysicalStrategy(loader));
      // Micronaut Data switches Hibernate to legacy id names (one hibernate_sequence).
      config.put("hibernate.id.db_structure_naming_strategy", "legacy");
    } else if (naming.snakeCase()) {
      config.put("hibernate.physical_naming_strategy", snakeCaseStrategy(loader));
    }
    if (naming.springJoinTables()) {
      config.put("hibernate.implicit_naming_strategy", springImplicitStrategy(loader));
    }
    config.putAll(settings);
    builder.applySettings(config);
    return builder;
  }

  /** Micronaut Data's own strategy when the application has it, otherwise the same rule. */
  private static Object micronautPhysicalStrategy(ClassLoader loader) {
    String micronaut = "io.micronaut.data.hibernate.naming.DefaultPhysicalNamingStrategy";
    try {
      Class.forName(micronaut, false, loader);
      return micronaut;
    } catch (ClassNotFoundException | LinkageError e) {
      // An instance, because the application's class loader cannot see Migrax's adapters.
      return new MicronautLikePhysicalNamingStrategy();
    }
  }

  /** Hibernate's CamelCaseToUnderscoresNamingStrategy (5.5+), otherwise the same rule. */
  private static Object snakeCaseStrategy(ClassLoader loader) {
    try {
      Class.forName(CAMEL_CASE_TO_UNDERSCORES, false, loader);
      return CAMEL_CASE_TO_UNDERSCORES;
    } catch (ClassNotFoundException | LinkageError e) {
      return new SnakeCasePhysicalNamingStrategy();
    }
  }

  private static Object springImplicitStrategy(ClassLoader loader) {
    String spring = "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy";
    try {
      Class.forName(spring, false, loader);
      return spring;
    } catch (ClassNotFoundException | LinkageError e) {
      // An instance, because the application's class loader cannot see Migrax's adapters.
      return new SpringLikeImplicitNamingStrategy();
    }
  }

  // ------------------------------------------------------------------ conversion

  /** Identity id columns for Hibernate versions without Column.isIdentity() (before 6.4). */
  private java.util.Set<Column> identityColumns = java.util.Set.of();

  private SchemaModel convert(Metadata metadata, Dialect dialect) {
    if (COLUMN_IS_IDENTITY == null) {
      identityColumns = identityColumns(metadata);
    }
    List<SchemaModel.Table> tables = new ArrayList<>();
    List<SchemaModel.Sequence> sequences = new ArrayList<>();
    boolean tableSequences = "mysql".equals(dialect.id());
    for (Namespace namespace : metadata.getDatabase().getNamespaces()) {
      for (Table table : namespace.getTables()) {
        if (!table.isPhysicalTable() || isView(table) || table.getSubselect() != null
            || table.isAbstractUnionTable()) {
          continue;
        }
        // MySQL has no sequences: Hibernate maps them to one-column next_val tables.
        if (tableSequences && columns(table).size() == 1
            && "next_val".equalsIgnoreCase(columns(table).get(0).getName())) {
          sequences.add(new SchemaModel.Sequence(table.getName(), 1L, 50L));
          continue;
        }
        tables.add(convert(table, metadata, dialect));
      }
      for (Sequence sequence : namespace.getSequences()) {
        sequences.add(new SchemaModel.Sequence(sequence.getName().getSequenceName().getText(),
            (long) sequence.getInitialValue(), (long) sequence.getIncrementSize()));
      }
    }
    tables.sort(java.util.Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(tables, sequences);
  }

  private SchemaModel.Table convert(Table table, Metadata metadata, Dialect dialect) {
    String name = table.getName();
    java.util.Set<String> uniqueColumns = new java.util.HashSet<>();
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (UniqueKey key : uniqueKeys(table)) {
      List<String> columns = key.getColumns().stream().map(Column::getName).toList();
      if (columns.size() == 1) {
        uniqueColumns.add(columns.get(0));
      } else {
        indexes.add(new SchemaModel.Index(
            constraintName(key.getName(), "uk_" + name + "_" + String.join("_", columns)),
            columns, true));
      }
    }
    for (Index index : indexes(table)) {
      List<String> columns = indexColumns(index).stream().map(Column::getName).toList();
      indexes.add(new SchemaModel.Index(
          constraintName(index.getName(), "idx_" + name + "_" + String.join("_", columns)),
          columns, isUnique(index)));
    }

    List<SchemaModel.Column> columns = new ArrayList<>();
    for (Column column : columns(table)) {
      columns.add(convert(column, metadata, dialect,
          column.isUnique() || uniqueColumns.contains(column.getName())));
    }
    columns.sort(java.util.Comparator.comparing(SchemaModel.Column::name));

    SchemaModel.PrimaryKey primaryKey = null;
    if (table.getPrimaryKey() != null && !table.getPrimaryKey().getColumns().isEmpty()) {
      String keyName = table.getPrimaryKey().getName();
      primaryKey = new SchemaModel.PrimaryKey(
          table.getPrimaryKey().getColumns().stream().map(Column::getName).toList(),
          keyName == null || GENERATED_NAME.matcher(keyName).matches()
              ? SchemaModel.primaryKeyConstraintName(name) : keyName);
    }

    List<SchemaModel.ForeignKey> keys = new ArrayList<>();
    for (ForeignKey key : table.getForeignKeys().values()) {
      if (!key.isCreationEnabled() || key.getReferencedTable() == null) {
        continue;
      }
      Table target = key.getReferencedTable();
      List<String> keyColumns = key.getColumns().stream().map(Column::getName).toList();
      List<Column> referenced = key.isReferenceToPrimaryKey() && target.getPrimaryKey() != null
          ? target.getPrimaryKey().getColumns() : key.getReferencedColumns();
      keys.add(new SchemaModel.ForeignKey(
          constraintName(key.getName(),
              "fk_" + name + "_" + String.join("_", keyColumns) + "_" + target.getName()),
          keyColumns, target.getName(), referenced.stream().map(Column::getName).toList()));
    }
    keys.sort(java.util.Comparator.comparing(SchemaModel.ForeignKey::name));
    return new SchemaModel.Table(name, columns, primaryKey, indexes, keys);
  }

  private SchemaModel.Column convert(Column column, Metadata metadata, Dialect dialect,
                                     boolean unique) {
    String hibernateType = sqlType(column, metadata);
    int code = sqlTypeCode(column, metadata);
    String logical = logicalType(code);
    // A @Lob mapped to varchar (Hibernate does this for @Lob String on CockroachDB, as
    // varchar(255)) is text: a large object must not be cut to 255 characters.
    if ("varchar".equals(logical) && column.getValue() != null
        && Boolean.TRUE.equals(call(column.getValue(), "isLob"))) {
      logical = "text";
      hibernateType = "text";
    }
    Integer length = null;
    Integer precision = null;
    Integer scale = null;
    if ("varchar".equals(logical) || "nvarchar".equals(logical) || "varbinary".equals(logical)) {
      // Long in Hibernate 6+, int (255 when unset) in Hibernate 5.
      Number size = (Number) call(column, "getLength");
      length = size == null || size.longValue() == 255L ? null
          : (int) Math.min(size.longValue(), Integer.MAX_VALUE);
    } else if ("decimal".equals(logical)) {
      precision = intValue(call(column, "getPrecision"));
      scale = intValue(call(column, "getScale"));
      if (precision != null && precision == 38 && scale != null && scale == 2) {
        precision = null;
        scale = null;
      }
    }
    SchemaModel.Column neutral = new SchemaModel.Column(column.getName(), logical,
        column.isNullable(), length, precision, scale, column.getDefaultValue(), unique,
        isIdentity(column), null, logical);
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
    return new SchemaModel.Column(column.getName(), hibernateType, column.isNullable(), length,
        precision, scale, column.getDefaultValue(), unique, isIdentity(column), null, logical);
  }

  private static String normalize(String type) {
    return type.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
        .replace(" (", "(").replace(", ", ",").trim();
  }

  /** Readable Migrax-style name instead of Hibernate's hashed FK/UK/IDX names. */
  private static String constraintName(String name, String readable) {
    if (name == null || name.isBlank() || GENERATED_NAME.matcher(name).matches()) {
      return SchemaModel.boundedName(readable.toLowerCase(Locale.ROOT));
    }
    return name;
  }

  private static String logicalType(int code) {
    return switch (code) {
      case 12, 1 -> "varchar";
      case -9, -15 -> "nvarchar";
      case -1, -16, 4001, 4002 -> "text";
      case 2005, 2011, 3005, 3006 -> "clob";
      case 4, 5, -6 -> "integer";
      case -5 -> "bigint";
      case 16, -7 -> "boolean";
      case 2, 3 -> "decimal";
      case 8 -> "double";
      case 6, 7 -> "float";
      case 91 -> "date";
      case 92, 2013 -> "time";
      case 93 -> "timestamp";
      case 2014, 3003 -> "timestamptz";
      case -2, -3, -4, 4003 -> "varbinary";
      case 2004, 3004 -> "blob";
      case 3000 -> "uuid";
      case 3001, 3018 -> "json";
      default -> "other";
    };
  }

  // ------------------------------------------------------------------ bean validation

  /**
   * Hibernate turns some Bean Validation constraints into DDL when a validator is present:
   * {@code @NotNull} makes a column NOT NULL, {@code @Size(max)} sets the length and
   * {@code @Digits} the precision and scale.
   */
  private static boolean beanValidationApplies(ClassLoader loader, Map<String, String> settings) {
    String mode = settings.getOrDefault("jakarta.persistence.validation.mode",
        settings.getOrDefault("javax.persistence.validation.mode", "auto"));
    if ("none".equalsIgnoreCase(mode) || "false".equalsIgnoreCase(
        settings.getOrDefault("hibernate.validator.apply_to_ddl", "true"))) {
      return false;
    }
    try {
      Class.forName("org.hibernate.validator.HibernateValidator", false, loader);
      return true;
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Calls {@code Column.getSqlTypeCode(...)} reflectively: its parameter is
   * {@code engine.spi.Mapping} in Hibernate 6 and {@code type.MappingContext} in Hibernate 7,
   * and Metadata implements whichever the running Hibernate has.
   */
  private static int sqlTypeCode(Column column, Metadata metadata) {
    for (java.lang.reflect.Method method : Column.class.getMethods()) {
      if (method.getName().equals("getSqlTypeCode") && method.getParameterCount() == 1
          && method.getParameterTypes()[0].isInstance(metadata)) {
        try {
          return (Integer) method.invoke(column, metadata);
        } catch (java.lang.reflect.InvocationTargetException e) {
          if (e.getCause() instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    throw new IllegalStateException("This Hibernate version has no Column.getSqlTypeCode for "
        + metadata.getClass().getName());
  }

  private static void applyBeanValidation(Metadata metadata) {
    for (PersistentClass entity : metadata.getEntityBindings()) {
      if (entity instanceof SingleTableSubclass || entity.getMappedClass() == null) {
        continue;
      }
      for (Property property : entity.getPropertyClosure()) {
        apply(entity.getMappedClass(), property);
      }
    }
  }

  private static void apply(Class<?> owner, Property property) {
    if (property.getValue() instanceof Component component) {
      Class<?> type = component.getComponentClass();
      if (type != null) {
        for (Property nested : properties(component)) {
          apply(type, nested);
        }
      }
      return;
    }
    Field field = field(owner, property.getName());
    if (field == null) {
      return;
    }
    Map<String, Annotation> constraints = new HashMap<>();
    for (Annotation annotation : field.getAnnotations()) {
      String type = annotation.annotationType().getName();
      if (type.startsWith("jakarta.validation.constraints.")
          || type.startsWith("javax.validation.constraints.")) {
        constraints.put(annotation.annotationType().getSimpleName(), annotation);
      }
    }
    if (constraints.isEmpty()) {
      return;
    }
    for (Selectable selectable : selectables(property.getValue())) {
      if (!(selectable instanceof Column column)) {
        continue;
      }
      if (constraints.containsKey("NotNull")) {
        column.setNullable(false);
      }
      Annotation size = constraints.get("Size");
      if (size != null) {
        Object max = value(size, "max");
        Number current = (Number) call(column, "getLength");
        if (max instanceof Integer limit && limit < Integer.MAX_VALUE
            && (current == null || current.longValue() == 255L)) {
          setNumber(column, "setLength", limit);
        }
      }
      Annotation digits = constraints.get("Digits");
      if (digits != null && value(digits, "integer") instanceof Integer integer
          && value(digits, "fraction") instanceof Integer fraction) {
        setNumber(column, "setPrecision", integer + fraction);
        setNumber(column, "setScale", fraction);
      }
    }
  }

  // ------------------------------------------------ Hibernate 5.x and 6.0 - 6.2 compatibility
  // Migrax compiles against Hibernate 6.6. Members that arrived later than Hibernate 5 are
  // reached through their earlier equivalents (mostly iterators instead of lists).

  /** Table.getColumns() exists from 6.2; before that, getColumnIterator(). */
  @SuppressWarnings("unchecked")
  private static List<Column> columns(Table table) {
    Object columns = call(table, "getColumns");
    if (columns instanceof java.util.Collection<?> collection) {
      return new ArrayList<>((java.util.Collection<Column>) collection);
    }
    List<Column> result = new ArrayList<>();
    ((java.util.Iterator<Column>) call(table, "getColumnIterator")).forEachRemaining(result::add);
    return result;
  }

  /** Table.getUniqueKeys() is public from 6.0; Hibernate 5 has getUniqueKeyIterator(). */
  @SuppressWarnings("unchecked")
  private static List<UniqueKey> uniqueKeys(Table table) {
    Object keys = call(table, "getUniqueKeys");
    if (keys instanceof Map<?, ?> byName) {
      return new ArrayList<>((java.util.Collection<UniqueKey>) byName.values());
    }
    List<UniqueKey> result = new ArrayList<>();
    ((java.util.Iterator<UniqueKey>) call(table, "getUniqueKeyIterator"))
        .forEachRemaining(result::add);
    return result;
  }

  /** Value.getSelectables() exists from 6.0; Hibernate 5 has getColumnIterator(). */
  @SuppressWarnings("unchecked")
  private static List<Selectable> selectables(Object value) {
    Object selectables = call(value, "getSelectables");
    if (selectables instanceof List<?> list) {
      return (List<Selectable>) list;
    }
    List<Selectable> result = new ArrayList<>();
    ((java.util.Iterator<Selectable>) call(value, "getColumnIterator"))
        .forEachRemaining(result::add);
    return result;
  }

  /** Component.getProperties() exists from 6.0; Hibernate 5 has getPropertyIterator(). */
  @SuppressWarnings("unchecked")
  private static List<Property> properties(Component component) {
    Object properties = call(component, "getProperties");
    if (properties instanceof List<?> list) {
      return (List<Property>) list;
    }
    List<Property> result = new ArrayList<>();
    ((java.util.Iterator<Property>) call(component, "getPropertyIterator"))
        .forEachRemaining(result::add);
    return result;
  }

  private static Integer intValue(Object number) {
    return number == null ? null : ((Number) number).intValue();
  }

  /** Calls a one-argument numeric setter whose parameter is int, long, Integer or Long. */
  private static void setNumber(Object target, String setter, int value) {
    for (java.lang.reflect.Method method : target.getClass().getMethods()) {
      if (method.getName().equals(setter) && method.getParameterCount() == 1) {
        Class<?> type = method.getParameterTypes()[0];
        Object argument = type == long.class || type == Long.class ? (Object) (long) value
            : type == int.class || type == Integer.class ? (Object) value : null;
        if (argument != null) {
          try {
            method.invoke(target, argument);
            return;
          } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
          }
        }
      }
    }
  }

  /** Table.isView() exists from Hibernate 6.3; earlier versions have no views in mappings. */
  private static boolean isView(Table table) {
    Object view = call(table, "isView");
    return Boolean.TRUE.equals(view);
  }

  /** Table.getIndexes() exists from 6.2; before that, getIndexIterator(). */
  @SuppressWarnings("unchecked")
  private static List<Index> indexes(Table table) {
    Object map = call(table, "getIndexes");
    if (map instanceof Map<?, ?> byName) {
      return new ArrayList<>((java.util.Collection<Index>) byName.values());
    }
    List<Index> indexes = new ArrayList<>();
    ((java.util.Iterator<Index>) call(table, "getIndexIterator")).forEachRemaining(indexes::add);
    return indexes;
  }

  /** Index.getColumns() exists from 6.2; before that, getColumnIterator(). */
  @SuppressWarnings("unchecked")
  private static List<Column> indexColumns(Index index) {
    Object columns = call(index, "getColumns");
    if (columns instanceof List<?> list) {
      return (List<Column>) list;
    }
    List<Column> result = new ArrayList<>();
    ((java.util.Iterator<Column>) call(index, "getColumnIterator")).forEachRemaining(result::add);
    return result;
  }

  /** Index.isUnique() exists from 6.3; before, unique constraints are only UniqueKeys. */
  private static boolean isUnique(Index index) {
    return Boolean.TRUE.equals(call(index, "isUnique"));
  }

  /**
   * Column.getSqlType(Metadata) exists from 6.1; 6.0 takes (TypeConfiguration, Dialect,
   * Mapping) and Hibernate 5 (Dialect, Mapping).
   */
  private static String sqlType(Column column, Metadata metadata) {
    Object type = call(column, "getSqlType", metadata);
    if (type != null) {
      return (String) type;
    }
    Object database = metadata.getDatabase();
    Object dialect = call(database, "getDialect");
    Object typeConfiguration = call(database, "getTypeConfiguration");
    if (typeConfiguration != null) {
      type = call(column, "getSqlType", typeConfiguration, dialect, metadata);
    }
    return type != null ? (String) type : (String) call(column, "getSqlType", dialect, metadata);
  }

  /**
   * Calls a public method whose parameters accept {@code args}; returns null when this
   * Hibernate version has no such method.
   */
  private static Object call(Object target, String name, Object... args) {
    for (java.lang.reflect.Method method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
        continue;
      }
      boolean fits = true;
      for (int i = 0; i < args.length; i++) {
        fits &= args[i] != null && method.getParameterTypes()[i].isInstance(args[i]);
      }
      if (fits) {
        try {
          return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
          if (e.getCause() instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return null;
  }

  /** Column.isIdentity() exists from Hibernate 6.4 on; null before. */
  private static final java.lang.reflect.Method COLUMN_IS_IDENTITY = isIdentityMethod();

  private static java.lang.reflect.Method isIdentityMethod() {
    try {
      return Column.class.getMethod("isIdentity");
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  private boolean isIdentity(Column column) {
    if (COLUMN_IS_IDENTITY == null) {
      return identityColumns.contains(column);
    }
    try {
      return (Boolean) COLUMN_IS_IDENTITY.invoke(column);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Id columns of entities whose id is {@code @GeneratedValue(strategy = IDENTITY)}. */
  private static java.util.Set<Column> identityColumns(Metadata metadata) {
    // By reference: Hibernate's Column.equals compares names, so every "id" would match.
    java.util.Set<Column> columns =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    for (PersistentClass entity : metadata.getEntityBindings()) {
      Property id = entity.getIdentifierProperty();
      if (id == null || entity.getMappedClass() == null || entity.getIdentifier() == null) {
        continue;
      }
      if (identityGenerated(entity.getMappedClass(), id.getName())) {
        for (Selectable selectable : selectables(entity.getIdentifier())) {
          if (selectable instanceof Column column) {
            columns.add(column);
          }
        }
      }
    }
    return columns;
  }

  private static boolean identityGenerated(Class<?> owner, String property) {
    List<java.lang.reflect.AnnotatedElement> places = new ArrayList<>();
    Field field = field(owner, property);
    if (field != null) {
      places.add(field);
    }
    String getter = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
    for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
      try {
        places.add(c.getDeclaredMethod(getter));
      } catch (NoSuchMethodException ignored) {
        // Field access, or declared in a superclass.
      }
    }
    for (java.lang.reflect.AnnotatedElement place : places) {
      for (Annotation annotation : place.getAnnotations()) {
        if (annotation.annotationType().getName().endsWith("persistence.GeneratedValue")
            && "IDENTITY".equals(String.valueOf(value(annotation, "strategy")))) {
          return true;
        }
      }
    }
    return false;
  }

  private static Field field(Class<?> type, String name) {
    for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
      try {
        return c.getDeclaredField(name);
      } catch (NoSuchFieldException ignored) {
        // Try the superclass.
      }
    }
    return null;
  }

  private static Object value(Annotation annotation, String attribute) {
    try {
      return annotation.annotationType().getMethod(attribute).invoke(annotation);
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }
}
