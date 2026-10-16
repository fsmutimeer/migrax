package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.persistence.descriptors.ClassDescriptor;
import org.eclipse.persistence.internal.databaseaccess.DatabasePlatform;
import org.eclipse.persistence.internal.databaseaccess.FieldTypeDefinition;
import org.eclipse.persistence.internal.jpa.EntityManagerFactoryDelegate;
import org.eclipse.persistence.internal.sessions.DatabaseSessionImpl;
import org.eclipse.persistence.sequencing.DefaultSequence;
import org.eclipse.persistence.sequencing.NativeSequence;
import org.eclipse.persistence.sequencing.Sequence;
import org.eclipse.persistence.sequencing.TableSequence;
import org.eclipse.persistence.tools.schemaframework.DefaultTableGenerator;
import org.eclipse.persistence.tools.schemaframework.FieldDefinition;
import org.eclipse.persistence.tools.schemaframework.ForeignKeyConstraint;
import org.eclipse.persistence.tools.schemaframework.IndexDefinition;
import org.eclipse.persistence.tools.schemaframework.TableDefinition;
import org.eclipse.persistence.tools.schemaframework.UniqueKeyConstraint;

/**
 * Reads the schema an application's EclipseLink would create, from EclipseLink's own mapping,
 * without connecting to a database: the persistence unit is only predeployed, its descriptors
 * are initialized, and EclipseLink's table generator lists the tables. Loaded against the
 * application's EclipseLink, like {@link HibernateSchemaReader} against its Hibernate.
 *
 * <p>Names EclipseLink makes up are upper case (ID, TAG); unquoted names are case-insensitive
 * in SQL, so they become lower case, like Migrax's other names. Quoted names keep their case,
 * and so do table names on MySQL and MariaDB, where table names are case-sensitive on Linux
 * and EclipseLink queries them as it spells them.
 */
public final class EclipseLinkSchemaReader {
  /** EclipseLink's platform per Migrax dialect, for the database's default types. */
  private static final Map<String, String> PLATFORMS = Map.of(
      "postgresql", "org.eclipse.persistence.platform.database.PostgreSQLPlatform",
      "cockroachdb", "org.eclipse.persistence.platform.database.PostgreSQLPlatform",
      "yugabytedb", "org.eclipse.persistence.platform.database.PostgreSQLPlatform",
      "mysql", "org.eclipse.persistence.platform.database.MySQLPlatform",
      "mariadb", "org.eclipse.persistence.platform.database.MySQLPlatform",
      "tidb", "org.eclipse.persistence.platform.database.MySQLPlatform",
      "sqlserver", "org.eclipse.persistence.platform.database.SQLServerPlatform",
      "oracle", "org.eclipse.persistence.platform.database.OraclePlatform",
      "h2", "org.eclipse.persistence.platform.database.H2Platform",
      "sqlite", "org.eclipse.persistence.platform.database.DatabasePlatform");
  /** The sequence name EclipseLink gives GenerationType.IDENTITY. */
  private static final String IDENTITY = "SEQ_GEN_IDENTITY";

  public SchemaModel read(List<Class<?>> classes, Map<String, String> settings, Dialect dialect)
      throws Exception {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    String platform = PLATFORMS.getOrDefault(dialect.id(),
        "org.eclipse.persistence.platform.database.DatabasePlatform");
    Map<String, Object> properties = new HashMap<>();
    settings.forEach((key, value) -> {
      if (key.startsWith("eclipselink.")) {
        properties.put(key, value);
      }
    });
    properties.put("eclipselink.target-database", platform);
    properties.put("eclipselink.logging.level", "OFF");
    properties.put("eclipselink.weaving", "false");
    properties.put("eclipselink.deploy-on-startup", "false");

    Object factory = createFactory(classes, properties, loader);
    try {
      EntityManagerFactoryDelegate delegate = (EntityManagerFactoryDelegate) factory.getClass()
          .getMethod("unwrap", Class.class).invoke(factory, EntityManagerFactoryDelegate.class);
      DatabaseSessionImpl session = (DatabaseSessionImpl) delegate.getSetupImpl().getSession();
      // Predeployed only: no login, no connection. Descriptors still name their classes.
      session.getProject().convertClassNamesToClasses(loader);
      session.initializeDescriptors();
      DatabasePlatform types = (DatabasePlatform) Class.forName(platform, true, loader)
          .getConstructor().newInstance();
      boolean caseSensitiveTables = dialect.id().equals("mysql") || dialect.id().equals("mariadb")
          || dialect.id().equals("tidb");
      EclipseLinkSchemaReader reader = new EclipseLinkSchemaReader();
      reader.ownLobTypes = caseSensitiveTables || dialect.id().equals("postgresql")
          || dialect.id().equals("cockroachdb") || dialect.id().equals("yugabytedb");
      return reader.convert(session, types, caseSensitiveTables);
    } finally {
      factory.getClass().getMethod("close").invoke(factory);
    }
  }

  /**
   * A container entity manager factory for the classes, through whichever persistence API
   * (jakarta or javax) the application's EclipseLink implements.
   */
  private static Object createFactory(List<Class<?>> classes, Map<String, Object> properties,
                                      ClassLoader loader) throws Exception {
    String api = interfaceExists("jakarta.persistence.spi.PersistenceUnitInfo", loader)
        ? "jakarta.persistence" : "javax.persistence";
    Class<?> unitInfo = Class.forName(api + ".spi.PersistenceUnitInfo", true, loader);
    Properties unitProperties = new Properties();
    unitProperties.putAll(properties);
    URL root = classes.get(0).getProtectionDomain().getCodeSource() == null ? null
        : classes.get(0).getProtectionDomain().getCodeSource().getLocation();
    List<String> names = classes.stream().map(Class::getName).toList();
    Object info = Proxy.newProxyInstance(loader, new Class<?>[] {unitInfo}, (proxy, method, args) ->
        switch (method.getName()) {
          case "getPersistenceUnitName" -> "migrax";
          case "getPersistenceProviderClassName" -> "org.eclipse.persistence.jpa.PersistenceProvider";
          case "getManagedClassNames" -> names;
          case "excludeUnlistedClasses" -> true;
          case "getClassLoader", "getNewTempClassLoader" -> loader;
          case "getProperties" -> unitProperties;
          case "getPersistenceUnitRootUrl" -> root;
          case "getJarFileUrls", "getMappingFileNames", "getQualifierAnnotationNames" -> List.of();
          case "getPersistenceXMLSchemaVersion" -> "2.2";
          case "getTransactionType" -> enumValue(api + ".spi.PersistenceUnitTransactionType",
              "RESOURCE_LOCAL", loader);
          case "getSharedCacheMode" -> enumValue(api + ".SharedCacheMode", "UNSPECIFIED", loader);
          case "getValidationMode" -> enumValue(api + ".ValidationMode", "NONE", loader);
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == args[0];
          case "toString" -> "Migrax persistence unit";
          default -> null;
        });
    Object provider = Class.forName("org.eclipse.persistence.jpa.PersistenceProvider", true, loader)
        .getConstructor().newInstance();
    Method create = provider.getClass().getMethod("createContainerEntityManagerFactory",
        unitInfo, Map.class);
    return create.invoke(provider, info, properties);
  }

  private static boolean interfaceExists(String name, ClassLoader loader) {
    try {
      Class.forName(name, false, loader);
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static Object enumValue(String type, String name, ClassLoader loader) throws Exception {
    return Enum.valueOf((Class<? extends Enum>) Class.forName(type, true, loader), name);
  }

  /** Whether table names keep EclipseLink's case (MySQL, MariaDB). */
  private boolean caseSensitiveTables;

  /** Whether large objects get EclipseLink's types (PostgreSQL, CockroachDB, MySQL, MariaDB). */
  private boolean ownLobTypes;

  private SchemaModel convert(DatabaseSessionImpl session, DatabasePlatform types,
                              boolean caseSensitiveTables) {
    this.caseSensitiveTables = caseSensitiveTables;
    // Identity columns, and the sequences the entities draw their ids from.
    Set<String> identityColumns = new HashSet<>();
    Map<String, SchemaModel.Sequence> sequences = new LinkedHashMap<>();
    Map<String, SchemaModel.Table> sequenceTables = new LinkedHashMap<>();
    for (ClassDescriptor descriptor : session.getProject().getOrderedDescriptors()) {
      if (!descriptor.usesSequenceNumbers() || descriptor.getSequenceNumberField() == null) {
        continue;
      }
      String name = descriptor.getSequenceNumberName();
      Sequence declared = descriptor.getSequence();
      // A native sequence is an identity column where the database has no sequences (MySQL).
      boolean nativeWithoutSequences = declared instanceof NativeSequence
          && !types.supportsSequenceObjects();
      if (IDENTITY.equals(name) || nativeWithoutSequences) {
        identityColumns.add(key(descriptor.getSequenceNumberField().getTableName(),
            descriptor.getSequenceNumberField().getName()));
        continue;
      }
      Sequence sequence = descriptor.getSequence();
      if (sequence instanceof DefaultSequence defaults) {
        sequence = defaults.getDefaultSequence();
      }
      if (sequence == null) {
        sequence = session.getPlatform().getDefaultSequence();
      }
      long initial = sequence == null ? 1 : sequence.getInitialValue();
      long allocation = sequence == null ? 50 : sequence.getPreallocationSize();
      if (sequence instanceof TableSequence table) {
        String tableName = tableName(table.getTableName());
        String nameColumn = identifier(table.getNameFieldName());
        String valueColumn = identifier(table.getCounterFieldName());
        sequenceTables.computeIfAbsent(tableName, t -> new SchemaModel.Table(t, List.of(
            new SchemaModel.Column(nameColumn, "varchar", false, 50, null, null, null, false),
            new SchemaModel.Column(valueColumn, "decimal", true, null, 38, 0, null, false)),
            new SchemaModel.PrimaryKey(List.of(nameColumn)), List.of(), List.of()));
        // The row's name is data: it keeps EclipseLink's spelling (SEQ_GEN).
        sequences.putIfAbsent(name, new SchemaModel.Sequence(name, initial, allocation,
            tableName, nameColumn, valueColumn));
      } else {
        // EclipseLink takes the value a sequence hands out as the end of a block of ids, so it
        // starts its sequences one block in: START WITH initial + allocation - 1.
        sequences.putIfAbsent(identifier(name), new SchemaModel.Sequence(identifier(name),
            initial + allocation - 1, allocation));
      }
    }

    List<SchemaModel.Table> tables = new ArrayList<>();
    for (TableDefinition definition : new DefaultTableGenerator(session.getProject())
        .generateDefaultTableCreator().getTableDefinitions()) {
      tables.add(table(definition, identityColumns, types));
    }
    tables.addAll(sequenceTables.values());
    return new SchemaModel(tables, new ArrayList<>(sequences.values()));
  }

  private SchemaModel.Table table(TableDefinition definition, Set<String> identityColumns,
                                  DatabasePlatform types) {
    String tableName = tableName(definition.getName());
    List<SchemaModel.Column> columns = new ArrayList<>();
    List<String> primaryKey = new ArrayList<>();
    for (FieldDefinition field : definition.getFields()) {
      String name = identifier(field.getName());
      boolean identity = identityColumns.contains(key(definition.getName(), field.getName()));
      columns.add(column(name, field, identity, types));
      if (field.isPrimaryKey()) {
        primaryKey.add(name);
      }
    }
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (UniqueKeyConstraint unique : definition.getUniqueKeys()) {
      indexes.add(new SchemaModel.Index(identifier(unique.getName()),
          identifiers(unique.getSourceFields()), true));
    }
    for (IndexDefinition index : definition.getIndexes()) {
      indexes.add(new SchemaModel.Index(identifier(index.getName()),
          identifiers(index.getFields()), index.isUnique()));
    }
    List<SchemaModel.ForeignKey> keys = new ArrayList<>();
    for (ForeignKeyConstraint key : definition.getForeignKeyMap().values()) {
      keys.add(new SchemaModel.ForeignKey(identifier(key.getName()),
          identifiers(key.getSourceFields()), tableName(key.getTargetTable()),
          identifiers(key.getTargetFields())));
    }
    return new SchemaModel.Table(tableName, columns,
        primaryKey.isEmpty() ? null : new SchemaModel.PrimaryKey(primaryKey), indexes, keys);
  }

  private SchemaModel.Column column(String name, FieldDefinition field, boolean identity,
                                    DatabasePlatform types) {
    boolean nullable = field.shouldAllowNull() && !field.isPrimaryKey();
    if (field.getTypeName() != null && !field.getTypeName().isBlank()) {
      // columnDefinition: the type exactly as written.
      String type = field.getTypeName().trim();
      return new SchemaModel.Column(name, type, nullable, null, null, null, null,
          field.isUnique(), identity, null, type);
    }
    Class<?> type = field.getType();
    String logical = logicalType(type, types);
    if (("blob".equals(logical) || "clob".equals(logical)) && ownLobTypes) {
      // Serialized values, byte and char arrays: where Migrax's own large-object type doesn't
      // fit EclipseLink (PostgreSQL's oid, MySQL's 64 KB blob), the type EclipseLink uses.
      FieldTypeDefinition definition = types.getFieldTypeDefinition(type);
      if (definition == null) {
        definition = types.getFieldTypeDefinition("blob".equals(logical) ? byte[].class
            : char[].class);
      }
      String sqlType = definition == null || definition.getName() == null ? logical
          : definition.getName().toLowerCase(Locale.ROOT);
      return new SchemaModel.Column(name, sqlType, nullable, null, null, null, null,
          field.isUnique(), identity, null, logical);
    }
    Integer length = null;
    Integer precision = null;
    Integer scale = null;
    if ("varchar".equals(logical)) {
      int size = field.getSize() > 0 ? field.getSize() : defaultSize(type, types, 255);
      length = size == 255 ? null : size;
    } else if ("decimal".equals(logical)) {
      precision = field.getSize() > 0 ? field.getSize() : defaultSize(type, types, 38);
      scale = field.getSize() > 0 ? field.getSubSize() : defaultSubSize(type, types);
    }
    return new SchemaModel.Column(name, logical, nullable, length, precision, scale, null,
        field.isUnique(), identity, null, logical);
  }

  /** Migrax's portable type for a Java type, as EclipseLink maps it. */
  private static String logicalType(Class<?> c, DatabasePlatform types) {
    if (c == null) return "varchar";
    if (c == String.class || c == Character.class || c == char.class) return "varchar";
    if (c == Integer.class || c == int.class || c == Short.class || c == short.class
        || c == Byte.class || c == byte.class) return "integer";
    if (c == Long.class || c == long.class) return "bigint";
    if (c == Boolean.class || c == boolean.class) return "boolean";
    if (c == java.math.BigDecimal.class || c == java.math.BigInteger.class) return "decimal";
    if (c == Double.class || c == double.class) return "double";
    if (c == Float.class || c == float.class) return "float";
    if (c == java.time.LocalDate.class || c == java.sql.Date.class) return "date";
    if (c == java.time.LocalDateTime.class || c == java.util.Date.class
        || c == java.util.Calendar.class || c == java.sql.Timestamp.class) return "timestamp";
    if (c == java.time.LocalTime.class || c == java.sql.Time.class) return "time";
    if (c == java.time.OffsetDateTime.class || c == java.time.ZonedDateTime.class
        || c == java.time.Instant.class) return "timestamptz";
    if (c == java.util.UUID.class) return "uuid";
    if (c == char[].class || c == Character[].class || c == java.sql.Clob.class) return "clob";
    // byte[], Blob and anything stored serialized.
    FieldTypeDefinition definition = types.getFieldTypeDefinition(c);
    String name = definition == null || definition.getName() == null ? ""
        : definition.getName().toLowerCase(Locale.ROOT);
    return name.contains("char") || name.contains("text") || name.contains("clob") ? "clob"
        : "blob";
  }

  private static int defaultSize(Class<?> type, DatabasePlatform types, int fallback) {
    FieldTypeDefinition definition = types.getFieldTypeDefinition(type);
    return definition != null && definition.isSizeAllowed() && definition.getDefaultSize() > 0
        ? definition.getDefaultSize() : fallback;
  }

  private static int defaultSubSize(Class<?> type, DatabasePlatform types) {
    FieldTypeDefinition definition = types.getFieldTypeDefinition(type);
    return definition == null ? 0 : definition.getDefaultSubSize();
  }

  private static String key(String table, String column) {
    return identifier(table) + "." + identifier(column);
  }

  /** A table name: lower case like other names, or as written where table names are
   *  case-sensitive. */
  private String tableName(String name) {
    if (!caseSensitiveTables || name == null) {
      return identifier(name);
    }
    String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    return bare.replace("\"", "").replace("`", "");
  }

  /** Unquoted names in lower case; quoted ones as written, without their quotes. */
  static String identifier(String name) {
    if (name == null) {
      return null;
    }
    String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    if (bare.length() > 1 && (bare.startsWith("\"") && bare.endsWith("\"")
        || bare.startsWith("`") && bare.endsWith("`"))) {
      return bare.substring(1, bare.length() - 1);
    }
    return bare.toLowerCase(Locale.ROOT);
  }

  private static List<String> identifiers(Collection<?> names) {
    List<String> result = new ArrayList<>();
    for (Object name : names) {
      result.add(identifier(String.valueOf(name)));
    }
    return result;
  }
}
