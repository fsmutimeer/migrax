package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.apache.openjpa.jdbc.conf.JDBCConfiguration;
import org.apache.openjpa.jdbc.kernel.TableJDBCSeq;
import org.apache.openjpa.jdbc.meta.ClassMapping;
import org.apache.openjpa.jdbc.meta.FieldMapping;
import org.apache.openjpa.jdbc.meta.MappingRepository;
import org.apache.openjpa.jdbc.schema.Column;
import org.apache.openjpa.jdbc.schema.ForeignKey;
import org.apache.openjpa.jdbc.schema.Index;
import org.apache.openjpa.jdbc.schema.Schema;
import org.apache.openjpa.jdbc.schema.Table;
import org.apache.openjpa.jdbc.schema.Unique;
import org.apache.openjpa.jdbc.sql.DBDictionary;
import org.apache.openjpa.meta.SequenceMetaData;
import org.apache.openjpa.meta.ValueStrategies;

/**
 * Reads the schema an application's OpenJPA would create, from OpenJPA's own mapping, without
 * connecting to a database: OpenJPA's mapping repository resolves each entity and lists the
 * tables, columns, keys and indexes it expects. Loaded against the application's OpenJPA, like
 * {@link EclipseLinkSchemaReader} against its EclipseLink.
 *
 * <p>OpenJPA writes names as the code spells them (createdAt, Tag) and unquoted, so they become
 * lower case, except table names on MySQL and MariaDB, which are case-sensitive on Linux.
 */
public final class OpenJpaSchemaReader {
  /** OpenJPA's dictionary per Migrax dialect. */
  private static final Map<String, String> DICTIONARIES = Map.of(
      "postgresql", "postgres", "cockroachdb", "postgres", "mysql", "mysql",
      "mariadb", "mariadb", "sqlserver", "sqlserver", "oracle", "oracle", "h2", "h2",
      "sqlite", "org.apache.openjpa.jdbc.sql.DBDictionary");

  private boolean caseSensitiveTables;
  private boolean ownLobTypes;
  private DBDictionary dictionary;

  public SchemaModel read(List<Class<?>> classes, Map<String, String> settings, Dialect dialect)
      throws Exception {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    Map<String, Object> properties = new HashMap<>();
    settings.forEach((key, value) -> {
      if (key.startsWith("openjpa.")) {
        properties.put(key, value);
      }
    });
    properties.put("openjpa.jdbc.DBDictionary", DICTIONARIES.getOrDefault(dialect.id(),
        "org.apache.openjpa.jdbc.sql.DBDictionary"));
    properties.put("openjpa.Log", "DefaultLevel=ERROR");
    // Only the mapping is read: the classes don't have to be enhanced.
    properties.put("openjpa.RuntimeUnenhancedClasses", "supported");
    properties.put("openjpa.DynamicEnhancementAgent", "false");
    caseSensitiveTables = dialect.id().equals("mysql") || dialect.id().equals("mariadb");
    ownLobTypes = caseSensitiveTables || dialect.id().equals("postgresql")
        || dialect.id().equals("cockroachdb");

    Object factory = createFactory(classes, properties, loader);
    try {
      JDBCConfiguration configuration = (JDBCConfiguration) factory.getClass()
          .getMethod("getConfiguration").invoke(factory);
      dictionary = configuration.getDBDictionaryInstance();
      MappingRepository repository = configuration.getMappingRepositoryInstance();
      Map<String, SchemaModel.Sequence> sequences = new LinkedHashMap<>();
      List<SchemaModel.Table> sequenceTables = new ArrayList<>();
      for (Class<?> type : classes) {
        if (type.getAnnotations().length == 0 || !isEntity(type)) {
          continue;
        }
        ClassMapping mapping = repository.getMapping(type, loader, true);
        for (FieldMapping id : mapping.getPrimaryKeyFieldMappings()) {
          if (id.getValueStrategy() == ValueStrategies.SEQUENCE) {
            sequence(id.getValueSequenceName(), repository, configuration, loader, sequences,
                sequenceTables);
          }
        }
      }
      List<SchemaModel.Table> tables = new ArrayList<>();
      for (Schema schema : repository.getSchemaGroup().getSchemas()) {
        for (Table table : schema.getTables()) {
          tables.add(table(table));
        }
      }
      for (SchemaModel.Table table : sequenceTables) {
        if (tables.stream().noneMatch(t -> t.name().equalsIgnoreCase(table.name()))) {
          tables.add(table);
        }
      }
      return new SchemaModel(tables, new ArrayList<>(sequences.values()));
    } finally {
      factory.getClass().getMethod("close").invoke(factory);
    }
  }

  private static boolean isEntity(Class<?> type) {
    for (java.lang.annotation.Annotation annotation : type.getAnnotations()) {
      String name = annotation.annotationType().getName();
      if (name.equals("jakarta.persistence.Entity") || name.equals("javax.persistence.Entity")) {
        return true;
      }
    }
    return false;
  }

  /**
   * A sequence an id draws from: a database sequence, or OpenJPA's sequence table (for the
   * "system" sequence a plain @GeneratedValue uses). OpenJPA adds the table's row itself.
   */
  private void sequence(String name, MappingRepository repository,
                        JDBCConfiguration configuration, ClassLoader loader,
                        Map<String, SchemaModel.Sequence> sequences,
                        List<SchemaModel.Table> sequenceTables) {
    Object instance = SequenceMetaData.NAME_SYSTEM.equals(name)
        ? configuration.getSequenceInstance() : null;
    SequenceMetaData metadata = instance == null
        ? repository.getSequenceMetaData(name, loader, true) : null;
    if (metadata != null) {
      String plugin = metadata.getSequencePlugin() == null ? ""
          : metadata.getSequencePlugin().toLowerCase(Locale.ROOT);
      if (plugin.isEmpty() || plugin.startsWith(SequenceMetaData.IMPL_NATIVE)) {
        String sequenceName = identifier(metadata.getSequence() != null
            ? metadata.getSequence() : name);
        long initial = Math.max(1, metadata.getInitialValue());
        long allocation = Math.max(1, metadata.getAllocate());
        sequences.putIfAbsent(sequenceName, new SchemaModel.Sequence(sequenceName, initial,
            allocation));
        return;
      }
      instance = metadata.getInstance(loader);
    }
    if (instance instanceof TableJDBCSeq table) {
      String tableName = tableName(table.getTable());
      String key = identifier(table.getPrimaryKeyColumn());
      String value = identifier(table.getSequenceColumn());
      sequenceTables.add(new SchemaModel.Table(tableName, List.of(
          new SchemaModel.Column(key, "integer", false, null, null, null, null, false),
          new SchemaModel.Column(value, "bigint", true, null, null, null, null, false)),
          new SchemaModel.PrimaryKey(List.of(key)), List.of(), List.of()));
    }
  }

  private SchemaModel.Table table(Table table) {
    String tableName = tableName(table.getIdentifier().getName());
    List<String> primaryKey = new ArrayList<>();
    if (table.getPrimaryKey() != null) {
      for (Column column : table.getPrimaryKey().getColumns()) {
        primaryKey.add(identifier(column.getIdentifier().getName()));
      }
    }
    // A unique constraint on one column is that column's unique flag, as Migrax models it for
    // every reader and the database; on several columns, a unique index.
    List<String> uniqueColumns = new ArrayList<>();
    List<SchemaModel.Index> indexes = new ArrayList<>();
    for (Unique unique : table.getUniques()) {
      List<String> columns = names(unique.getColumns());
      if (columns.size() == 1) {
        uniqueColumns.add(columns.get(0));
      } else {
        indexes.add(new SchemaModel.Index(identifier(unique.getIdentifier().getName()), columns,
            true));
      }
    }
    List<SchemaModel.Column> columns = new ArrayList<>();
    for (Column column : table.getColumns()) {
      String name = identifier(column.getIdentifier().getName());
      columns.add(column(name, column, primaryKey.contains(name), uniqueColumns.contains(name)));
    }
    for (Index index : table.getIndexes()) {
      indexes.add(new SchemaModel.Index(identifier(index.getIdentifier().getName()),
          names(index.getColumns()), index.isUnique()));
    }
    List<SchemaModel.ForeignKey> keys = new ArrayList<>();
    for (ForeignKey key : table.getForeignKeys()) {
      List<String> source = names(key.getColumns());
      // OpenJPA leaves its foreign keys unnamed: they get fk_<table>_<column>.
      String name = key.getIdentifier() != null && key.getIdentifier().getName() != null
          ? identifier(key.getIdentifier().getName())
          : foreignKeyName(tableName, source);
      keys.add(new SchemaModel.ForeignKey(name, source,
          tableName(key.getPrimaryKeyTable().getIdentifier().getName()),
          names(key.getPrimaryKeyColumns())));
    }
    return new SchemaModel.Table(tableName, columns,
        primaryKey.isEmpty() ? null : new SchemaModel.PrimaryKey(primaryKey), indexes, keys);
  }

  private SchemaModel.Column column(String name, Column column, boolean primaryKey,
                                    boolean unique) {
    boolean nullable = !column.isNotNull() && !primaryKey;
    String logical = logicalType(column.getType());
    if (("blob".equals(logical) || "clob".equals(logical)) && ownLobTypes) {
      // Where Migrax's own large-object type doesn't fit OpenJPA (PostgreSQL's oid, MySQL's
      // 64 KB blob), the type OpenJPA uses.
      String sqlType = dictionary.getTypeName(column).toLowerCase(Locale.ROOT);
      return new SchemaModel.Column(name, sqlType, nullable, null, null, null, null, unique,
          column.isAutoAssigned(), null, logical);
    }
    Integer length = null;
    Integer precision = null;
    Integer scale = null;
    if ("varchar".equals(logical) && column.getSize() > 0 && column.getSize() != 255) {
      length = column.getSize();
    } else if ("decimal".equals(logical) && column.getSize() > 0) {
      precision = column.getSize();
      scale = column.getDecimalDigits();
    }
    return new SchemaModel.Column(name, logical, nullable, length, precision, scale, null,
        unique, column.isAutoAssigned(), null, logical);
  }

  /** Migrax's portable type for a JDBC type. */
  private static String logicalType(int type) {
    return switch (type) {
      case Types.BIGINT -> "bigint";
      case Types.INTEGER, Types.SMALLINT, Types.TINYINT -> "integer";
      case Types.BIT, Types.BOOLEAN -> "boolean";
      case Types.NUMERIC, Types.DECIMAL -> "decimal";
      case Types.DOUBLE, Types.FLOAT -> "double";
      case Types.REAL -> "float";
      case Types.DATE -> "date";
      case Types.TIME -> "time";
      case Types.TIMESTAMP -> "timestamp";
      case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> "timestamptz";
      case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR -> "clob";
      case Types.BLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY -> "blob";
      default -> "varchar";
    };
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

  /** A table name: lower case, or as written where table names are case-sensitive. */
  private String tableName(String name) {
    if (!caseSensitiveTables || name == null) {
      return identifier(name);
    }
    String bare = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
    return bare.replace("\"", "").replace("`", "");
  }

  private static List<String> names(Column[] columns) {
    List<String> names = new ArrayList<>();
    for (Column column : columns) {
      names.add(identifier(column.getIdentifier().getName()));
    }
    return names;
  }

  private static String foreignKeyName(String table, List<String> columns) {
    String name = ("fk_" + table + "_" + String.join("_", columns)).toLowerCase(Locale.ROOT);
    return name.length() <= 60 ? name
        : name.substring(0, 51) + "_" + String.format(Locale.ROOT, "%08x", name.hashCode());
  }

  /**
   * A container entity manager factory for the classes, through whichever persistence API
   * (jakarta or javax) the application's OpenJPA implements.
   */
  private static Object createFactory(List<Class<?>> classes, Map<String, Object> properties,
                                      ClassLoader loader) throws Exception {
    String api = exists("jakarta.persistence.spi.PersistenceUnitInfo", loader)
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
          case "getPersistenceProviderClassName" ->
              "org.apache.openjpa.persistence.PersistenceProviderImpl";
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
    Object provider = Class.forName("org.apache.openjpa.persistence.PersistenceProviderImpl",
        true, loader).getConstructor().newInstance();
    Method create = provider.getClass().getMethod("createContainerEntityManagerFactory",
        unitInfo, Map.class);
    return create.invoke(provider, info, properties);
  }

  private static boolean exists(String name, ClassLoader loader) {
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
}
