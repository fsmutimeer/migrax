package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;
import org.migrax.util.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.jdbc.core.mapping.JdbcMappingContext;
import org.springframework.data.relational.core.mapping.AggregatePath;
import org.springframework.data.relational.core.mapping.RelationalPersistentEntity;
import org.springframework.data.relational.core.mapping.RelationalPersistentProperty;
import org.springframework.data.relational.core.sql.IdentifierProcessing;
import org.springframework.data.relational.core.sql.SqlIdentifier;

/**
 * Reads the schema an application's Spring Data JDBC works with, from Spring Data JDBC's own
 * mapping, without a database or a Spring context: a mapping context is built for the aggregate
 * roots, and every path through each aggregate gives its table, columns, back-reference columns
 * (child to parent) and key columns (list index, map key), exactly as Spring Data JDBC's own
 * SQL names them. Loaded against the application's Spring Data JDBC (3.0 and newer), like
 * {@link HibernateSchemaReader} against its Hibernate.
 *
 * <p>Spring Data JDBC quotes every name. Names it derives (from class and property names) follow
 * the database's letter case; names written in annotations ({@code @Table("customers")}) are
 * sent exactly as written. Where that differs from what an unquoted name becomes (lower case on
 * H2 and Oracle), the name is an {@linkplain SchemaModel#exactName exact name}, so the SQL
 * Migrax writes names the same table.
 *
 * @since 0.17.0
 */
public final class SpringDataJdbcSchemaReader {
  /** Spring's {@code IdentifierProcessing.create} changed its return type in 4.0. */
  private static final IdentifierProcessing UPPER = casing(true);
  private static final IdentifierProcessing LOWER = casing(false);

  private JdbcMappingContext context;
  private Dialect dialect;
  private final Map<String, Table> tables = new LinkedHashMap<>();
  private final Map<String, SchemaModel.Sequence> sequences = new LinkedHashMap<>();

  /**
   * Reads the aggregates whose roots are {@code classes}.
   *
   * @param settings unused today; kept for the same signature as the other readers
   */
  public SchemaModel read(List<Class<?>> classes, Map<String, String> settings, Dialect dialect) {
    this.dialect = dialect;
    quietSpringLogging();
    context = new JdbcMappingContext();
    context.setSimpleTypeHolder(new JdbcCustomConversions().getSimpleTypeHolder());
    Set<Class<?>> parts = new HashSet<>();
    for (Class<?> type : classes) {
      collectParts(context.getRequiredPersistentEntity(type), parts, new HashSet<>());
    }
    for (Class<?> type : classes) {
      if (parts.contains(type)) {
        // @Table on an entity inside another aggregate: it is read as part of that aggregate.
        continue;
      }
      RelationalPersistentEntity<?> entity = context.getRequiredPersistentEntity(type);
      Path root = Path.root(context, entity);
      Table table = table(root.table());
      addProperties(root, entity, table, false);
      table.primaryKey(idColumns(root, entity));
    }
    List<SchemaModel.Table> result = new ArrayList<>();
    for (Table table : tables.values()) {
      result.add(table.build());
    }
    result.sort(java.util.Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(result, new ArrayList<>(sequences.values()));
  }

  /**
   * Spring logs through the application's SLF4J; an application's Logback without its
   * configuration prints every DEBUG line (each converter Spring Data registers). Spring's
   * loggers are set to WARN when the application uses Logback.
   */
  private static void quietSpringLogging() {
    ClassLoader loader = JdbcMappingContext.class.getClassLoader();
    try {
      Object logger = Class.forName("org.slf4j.LoggerFactory", true, loader)
          .getMethod("getLogger", String.class).invoke(null, "org.springframework");
      Class<?> level = Class.forName("ch.qos.logback.classic.Level", true, loader);
      logger.getClass().getMethod("setLevel", level).invoke(logger,
          level.getField("WARN").get(null));
    } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
      // Another logging library, or none: nothing to quiet.
    }
  }

  /** The entity types inside an aggregate (not its root): child entities, at any depth. */
  private void collectParts(RelationalPersistentEntity<?> entity, Set<Class<?>> parts,
                            Set<Class<?>> seen) {
    if (!seen.add(entity.getType())) {
      return;
    }
    for (RelationalPersistentProperty property : entity) {
      if (property.isEntity()) {
        RelationalPersistentEntity<?> part = context.getRequiredPersistentEntity(
            property.getActualType());
        if (!property.isEmbedded()) {
          parts.add(part.getType());
        }
        collectParts(part, parts, seen);
      }
    }
  }

  /** The columns of an entity's properties, and the tables of its child entities. */
  private void addProperties(Path parent, RelationalPersistentEntity<?> entity, Table table,
                             boolean embedded) {
    for (RelationalPersistentProperty property : entity) {
      Path path = parent.append(property);
      if (path.isEmbedded()) {
        addProperties(path, path.leafEntity(), table, true);
      } else if (path.isEntity()) {
        addChildTable(path);
      } else if (property.isCollectionLike() && property.getType() != byte[].class) {
        Log.warn("{}.{}: a collection of simple values is stored as an array, which Migrax "
            + "doesn't write; the column is left out.", entity.getType().getSimpleName(),
            property.getName());
      } else {
        table.add(column(path.column(), property, embedded));
      }
    }
  }

  /** The table of a child entity: back-reference and key columns, then its own properties. */
  private void addChildTable(Path path) {
    Table table = table(path.table());
    Path owner = path.idDefiningParent();
    List<String> backReferences = new ArrayList<>();
    List<String> referenced = new ArrayList<>();
    for (BackReference reference : path.backReferences()) {
      String name = name(reference.column());
      backReferences.add(name);
      referenced.add(name(reference.referencedColumn()));
      table.add(new SchemaModel.Column(name, logicalType(reference.type()), false, null,
          null, null, null, false, false, null, logicalType(reference.type())));
    }
    List<String> key = new ArrayList<>(backReferences);
    // A list or map element without an id between this table and the parent with the id:
    // its key is in every row here too, so the rows know which element they belong to.
    List<Path> keyed = new ArrayList<>();
    for (Path step = path; step.length() > owner.length(); step = step.parent()) {
      if (step.isQualified()) {
        keyed.add(0, step);
      }
    }
    for (Path step : keyed) {
      String qualifier = name(step.qualifierColumn());
      key.add(qualifier);
      String type = logicalType(step.qualifierType());
      table.add(new SchemaModel.Column(qualifier, type, false, null, null, null, null, false,
          false, null, type));
    }
    RelationalPersistentEntity<?> entity = path.leafEntity();
    addProperties(path, entity, table, false);
    if (entity.hasIdProperty()) {
      table.primaryKey(idColumns(path, entity));
    } else if (path.isQualified() || !path.isMultiValued()) {
      // A list or map entry is its parent and its key; a one-to-one child is its parent.
      table.primaryKey(key);
    }
    String parentTable = name(owner.table());
    table.foreignKey(new SchemaModel.ForeignKey(SchemaModel.boundedName("fk_"
        + plain(table.name) + "_" + plain(parentTable)), backReferences, parentTable,
        referenced));
  }

  /** The primary key columns of an entity at {@code path}: its id, or the id's parts. */
  private List<String> idColumns(Path path, RelationalPersistentEntity<?> entity) {
    if (!entity.hasIdProperty()) {
      return List.of();
    }
    Path id = path.append(entity.getRequiredIdProperty());
    if (!id.isEmbedded()) {
      return List.of(name(id.column()));
    }
    List<String> columns = new ArrayList<>();
    for (RelationalPersistentProperty part : id.leafEntity()) {
      columns.add(name(id.append(part).column()));
    }
    return columns;
  }

  private SchemaModel.Column column(SqlIdentifier identifier,
                                    RelationalPersistentProperty property, boolean embedded) {
    Class<?> type = valueType(property);
    String name = name(identifier);
    boolean primitive = property.getType().isPrimitive() && !embedded;
    if (property.isIdProperty() && !embedded) {
      String sequence = sequence(property);
      boolean generated = sequence == null && (type == Long.class || type == long.class
          || type == Integer.class || type == int.class || type == Short.class
          || type == short.class || type == java.math.BigInteger.class);
      if (sequence != null) {
        sequences.putIfAbsent(sequence, new SchemaModel.Sequence(sequence, 1L, 1L));
      }
      String logical = logicalType(type);
      return new SchemaModel.Column(name, logical, false, null, null, null, null, false,
          generated, null, logical);
    }
    if (type == byte[].class && dialect instanceof org.migrax.dialect.PostgresDialect) {
      // Spring Data JDBC writes bytes, which PostgreSQL keeps in bytea, not in a large object.
      return new SchemaModel.Column(name, "bytea", !primitive, null, null, null, null, false,
          false, null, "blob");
    }
    String logical = logicalType(type);
    Integer scale = type == java.math.BigInteger.class ? 0 : null;
    return new SchemaModel.Column(name, logical, !primitive, null,
        scale == null ? null : 38, scale, null, false, false, null, logical);
  }

  /** The Java type stored in a column: an aggregate reference stores the other root's id. */
  private static Class<?> valueType(RelationalPersistentProperty property) {
    if (AggregateReference.class.isAssignableFrom(property.getType())) {
      // From the field: Spring Data's own type information moved package in 4.0.
      java.lang.reflect.Field field = property.getField();
      if (field != null
          && field.getGenericType() instanceof java.lang.reflect.ParameterizedType generic
          && generic.getActualTypeArguments().length == 2
          && generic.getActualTypeArguments()[1] instanceof Class<?> id) {
        return id;
      }
      return Long.class;
    }
    return property.getType();
  }

  /** The sequence an id draws from ({@code @Sequence}, Spring Data JDBC 3.5+), or null. */
  private String sequence(RelationalPersistentProperty property) {
    try {
      Method method = property.getClass().getMethod("getSequence");
      Object sequence = method.invoke(property);
      return sequence instanceof SqlIdentifier identifier ? name(identifier) : null;
    } catch (ReflectiveOperationException e) {
      return null;
    }
  }

  /** Migrax's portable type for a Java type, as Spring Data JDBC writes it. */
  private static String logicalType(Class<?> c) {
    if (c == null) return "varchar";
    if (c == String.class || c == Character.class || c == char.class) return "varchar";
    if (c == Integer.class || c == int.class || c == Short.class || c == short.class
        || c == Byte.class || c == byte.class) return "integer";
    if (c == Long.class || c == long.class) return "bigint";
    if (c == Boolean.class || c == boolean.class) return "boolean";
    if (c == java.math.BigDecimal.class || c == java.math.BigInteger.class) return "decimal";
    if (c == Double.class || c == double.class) return "double";
    if (c == Float.class || c == float.class) return "float";
    if (c == java.util.UUID.class) return "uuid";
    if (c == java.time.LocalDate.class || c == java.sql.Date.class) return "date";
    if (c == java.time.LocalDateTime.class || c == java.util.Date.class
        || c == java.sql.Timestamp.class) return "timestamp";
    if (c == java.time.OffsetDateTime.class || c == java.time.ZonedDateTime.class
        || c == java.time.Instant.class) return "timestamptz";
    if (c == java.time.LocalTime.class || c == java.sql.Time.class) return "time";
    if (c == byte[].class) return "blob";
    // Spring Data JDBC stores enums by name.
    if (c.isEnum()) return "varchar";
    return "text";
  }

  /**
   * A name as Migrax keeps it: lower case where SQL without quotes names the same object as
   * Spring Data JDBC's quoted name, otherwise an exact name.
   */
  private String name(SqlIdentifier identifier) {
    SqlIdentifier last = identifier;
    for (SqlIdentifier part : identifier) {
      // schema.table: the schema is left out, as for JPA entities.
      last = part;
    }
    String reference = last.getReference();
    String lower = reference.toLowerCase(Locale.ROOT);
    // Derived names take the database's letter case; written ones keep their own.
    boolean derived = !last.toSql(UPPER).equals(last.toSql(LOWER));
    Dialect.NameCase unquoted = dialect.unquotedNames();
    if (unquoted == Dialect.NameCase.UPPER) {
      String sent = derived ? reference.toUpperCase(Locale.ROOT) : reference;
      boolean plain = sent.equals(sent.toUpperCase(Locale.ROOT))
          && dialect.identifier(lower).equals(lower);
      return plain ? lower : "\"" + sent + "\"";
    }
    if (unquoted == Dialect.NameCase.LOWER) {
      String sent = derived ? lower : reference;
      return sent.equals(lower) ? lower : "\"" + sent + "\"";
    }
    // MySQL, SQL Server, SQLite keep the case of unquoted names.
    return derived ? lower : reference;
  }

  /** Identifier processing without quotes that changes letter case. */
  private static IdentifierProcessing casing(boolean upper) {
    return new IdentifierProcessing() {
      @Override
      public String quote(String identifier) {
        return identifier;
      }

      @Override
      public String standardizeLetterCase(String identifier) {
        return upper ? identifier.toUpperCase(Locale.ROOT) : identifier.toLowerCase(Locale.ROOT);
      }
    };
  }

  /** A name for building constraint names: lower case, no quotes. */
  private static String plain(String name) {
    return SchemaModel.bareName(name).toLowerCase(Locale.ROOT);
  }

  private Table table(SqlIdentifier identifier) {
    String name = name(identifier);
    return tables.computeIfAbsent(name, Table::new);
  }

  /**
   * A table being built. An entity that is both an aggregate root and part of another aggregate
   * maps both to one table: its columns are merged.
   */
  private static final class Table {
    private final String name;
    private final Map<String, SchemaModel.Column> columns = new LinkedHashMap<>();
    private List<String> primaryKey = List.of();
    private final Map<String, SchemaModel.ForeignKey> foreignKeys = new LinkedHashMap<>();

    Table(String name) {
      this.name = name;
    }

    void add(SchemaModel.Column column) {
      columns.putIfAbsent(column.name(), column);
    }

    void primaryKey(List<String> columns) {
      if (primaryKey.isEmpty()) {
        primaryKey = List.copyOf(columns);
      }
    }

    void foreignKey(SchemaModel.ForeignKey key) {
      foreignKeys.putIfAbsent(key.name(), key);
    }

    SchemaModel.Table build() {
      List<SchemaModel.Column> built = new ArrayList<>();
      for (SchemaModel.Column c : columns.values()) {
        built.add(!primaryKey.contains(c.name()) || !c.nullable() ? c
            : new SchemaModel.Column(c.name(), c.sqlType(), false, c.length(), c.precision(),
                c.scale(), c.defaultValue(), c.unique(), c.identity(), c.sequenceName(),
                c.logicalType(), c.comment()));
      }
      return new SchemaModel.Table(name, built,
          primaryKey.isEmpty() ? null : new SchemaModel.PrimaryKey(primaryKey),
          List.of(), new ArrayList<>(foreignKeys.values()));
    }
  }

  /** A back-reference column, the parent's column it holds, and that column's Java type. */
  private record BackReference(SqlIdentifier column, SqlIdentifier referencedColumn,
                               Class<?> type) {}

  /**
   * A property path through an aggregate. Spring Data JDBC 3.2 and newer describe it with
   * {@link AggregatePath}; 3.0 and 3.1 with {@code PersistentPropertyPathExtension}, read
   * through reflection.
   */
  private interface Path {
    static Path root(JdbcMappingContext context, RelationalPersistentEntity<?> entity) {
      try {
        return new Aggregate(context.getAggregatePath(entity));
      } catch (LinkageError e) {
        // 3.0 and 3.1 have no AggregatePath.
        return Extension.root(context, entity);
      }
    }

    Path append(RelationalPersistentProperty property);

    boolean isEntity();

    boolean isEmbedded();

    boolean isQualified();

    boolean isMultiValued();

    RelationalPersistentEntity<?> leafEntity();

    SqlIdentifier table();

    SqlIdentifier column();

    SqlIdentifier qualifierColumn();

    Class<?> qualifierType();

    Path idDefiningParent();

    Path parent();

    int length();

    List<BackReference> backReferences();
  }

  private record Aggregate(AggregatePath path) implements Path {
    public Path append(RelationalPersistentProperty property) {
      return new Aggregate(path.append(property));
    }

    public boolean isEntity() {
      return path.isEntity();
    }

    public boolean isEmbedded() {
      return path.isEmbedded();
    }

    public boolean isQualified() {
      return path.isQualified();
    }

    public boolean isMultiValued() {
      return path.isMultiValued();
    }

    public RelationalPersistentEntity<?> leafEntity() {
      return path.getLeafEntity();
    }

    public SqlIdentifier table() {
      return path.getTableInfo().qualifiedTableName();
    }

    public SqlIdentifier column() {
      return path.getColumnInfo().name();
    }

    public SqlIdentifier qualifierColumn() {
      return path.getTableInfo().qualifierColumnInfo().name();
    }

    public Class<?> qualifierType() {
      return path.getTableInfo().qualifierColumnType();
    }

    public Path idDefiningParent() {
      return new Aggregate(path.getIdDefiningParentPath());
    }

    public Path parent() {
      return new Aggregate(path.getParentPath());
    }

    public int length() {
      return path.getLength();
    }

    public List<BackReference> backReferences() {
      AggregatePath.TableInfo info = path.getTableInfo();
      List<BackReference> references = new ArrayList<>();
      try {
        // 4.0+: one column per part of a composite id.
        Object infos = info.getClass().getMethod("backReferenceColumnInfos").invoke(info);
        Method forEach = infos.getClass().getMethod("forEach",
            java.util.function.BiConsumer.class);
        java.util.function.BiConsumer<AggregatePath, AggregatePath.ColumnInfo> add =
            (idPath, column) -> references.add(new BackReference(column.name(),
                idPath.getColumnInfo().name(), valueType(idPath.getRequiredLeafProperty())));
        forEach.invoke(infos, add);
        return references;
      } catch (NoSuchMethodException e) {
        AggregatePath owner = path.getIdDefiningParentPath();
        RelationalPersistentProperty id = owner.getRequiredIdProperty();
        references.add(new BackReference(info.reverseColumnInfo().name(),
            owner.append(id).getColumnInfo().name(), valueType(id)));
        return references;
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /** Spring Data JDBC 3.0 and 3.1: {@code PersistentPropertyPathExtension}. */
  private record Extension(JdbcMappingContext context, Object extension) implements Path {
    private static final String TYPE =
        "org.springframework.data.relational.core.mapping.PersistentPropertyPathExtension";

    static Path root(JdbcMappingContext context, RelationalPersistentEntity<?> entity) {
      try {
        Class<?> type = Class.forName(TYPE, true, JdbcMappingContext.class.getClassLoader());
        return new Extension(context, type.getConstructor(
            org.springframework.data.mapping.context.MappingContext.class,
            RelationalPersistentEntity.class).newInstance(context, entity));
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException("This Spring Data JDBC version is not supported", e);
      }
    }

    private Object call(String method, Object... arguments) {
      try {
        for (Method candidate : extension.getClass().getMethods()) {
          if (candidate.getName().equals(method)
              && candidate.getParameterCount() == arguments.length) {
            return candidate.invoke(extension, arguments);
          }
        }
        throw new NoSuchMethodException(method);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    }

    public Path append(RelationalPersistentProperty property) {
      return new Extension(context, call("extendBy", property));
    }

    public boolean isEntity() {
      return (Boolean) call("isEntity");
    }

    public boolean isEmbedded() {
      return (Boolean) call("isEmbedded");
    }

    public boolean isQualified() {
      return (Boolean) call("isQualified");
    }

    public boolean isMultiValued() {
      return (Boolean) call("isMultiValued");
    }

    public RelationalPersistentEntity<?> leafEntity() {
      return (RelationalPersistentEntity<?>) call("getRequiredLeafEntity");
    }

    public SqlIdentifier table() {
      return (SqlIdentifier) call("getTableName");
    }

    public SqlIdentifier column() {
      return (SqlIdentifier) call("getColumnName");
    }

    public SqlIdentifier qualifierColumn() {
      return (SqlIdentifier) call("getQualifierColumn");
    }

    public Class<?> qualifierType() {
      return (Class<?>) call("getQualifierColumnType");
    }

    public Path idDefiningParent() {
      return new Extension(context, call("getIdDefiningParentPath"));
    }

    public Path parent() {
      return new Extension(context, call("getParentPath"));
    }

    public int length() {
      return (Integer) call("getLength");
    }

    public List<BackReference> backReferences() {
      Extension owner = (Extension) idDefiningParent();
      RelationalPersistentProperty id = (RelationalPersistentProperty) owner.call(
          "getRequiredIdProperty");
      return List.of(new BackReference((SqlIdentifier) call("getReverseColumnName"),
          owner.append(id).column(), valueType(id)));
    }
  }
}
