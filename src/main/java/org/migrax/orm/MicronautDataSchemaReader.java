package org.migrax.orm;

import org.migrax.dialect.Dialect;
import org.migrax.model.SchemaModel;
import org.migrax.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.model.Association;
import io.micronaut.data.model.DataType;
import io.micronaut.data.model.Embedded;
import io.micronaut.data.model.PersistentEntity;
import io.micronaut.data.model.PersistentEntityUtils;
import io.micronaut.data.model.PersistentProperty;
import io.micronaut.data.model.naming.NamingStrategy;
import io.micronaut.data.model.runtime.RuntimePersistentEntity;

/**
 * Reads the schema an application's Micronaut Data (JDBC, R2DBC) works with, from the mapping
 * Micronaut's annotation processor compiled into the entity classes, without a database or an
 * application context. Tables, columns, join tables, id generation and names follow the rules
 * Micronaut Data's own schema generation uses, through its public naming API, so they match the
 * SQL the application sends with the project's Micronaut Data version (4 or 5). Loaded against
 * the application's Micronaut Data, like {@link HibernateSchemaReader} against its Hibernate.
 *
 * @since 0.17.0
 */
public final class MicronautDataSchemaReader {
  private static final String JOIN_TABLE = "io.micronaut.data.annotation.sql.JoinTable";
  private static final String JOIN_COLUMNS = "io.micronaut.data.annotation.sql.JoinColumns";
  private static final String MAPPED_ENTITY = "io.micronaut.data.annotation.MappedEntity";
  private static final String MAPPED_PROPERTY = "io.micronaut.data.annotation.MappedProperty";
  private static final List<String> PERSISTENCE_COLUMNS = List.of("jakarta.persistence.Column",
      "javax.persistence.Column");

  private Dialect dialect;
  private final Map<String, SchemaModel.Table> tables = new LinkedHashMap<>();
  private final Map<String, SchemaModel.Sequence> sequences = new LinkedHashMap<>();

  /**
   * Reads the entities ({@code @MappedEntity} classes).
   *
   * @param settings unused today; kept for the same signature as the other readers
   */
  public SchemaModel read(List<Class<?>> classes, Map<String, String> settings, Dialect dialect) {
    this.dialect = dialect;
    for (Class<?> type : classes) {
      RuntimePersistentEntity<?> entity = new RuntimePersistentEntity<>(type);
      for (SchemaModel.Table joinTable : joinTables(entity)) {
        tables.putIfAbsent(joinTable.name(), joinTable);
      }
      SchemaModel.Table table = table(entity);
      tables.put(table.name(), table);
    }
    List<SchemaModel.Table> result = new ArrayList<>(tables.values());
    result.sort(java.util.Comparator.comparing(SchemaModel.Table::name));
    return new SchemaModel(result, new ArrayList<>(sequences.values()));
  }

  /** The entity's own table: id, version, then every property, embedded ones flattened. */
  private SchemaModel.Table table(PersistentEntity entity) {
    String table = name(entity.getPersistedName(), entity);
    NamingStrategy naming = entity.getNamingStrategy();
    List<SchemaModel.Column> columns = new ArrayList<>();
    List<String> primaryKey = new ArrayList<>();
    List<SchemaModel.ForeignKey> foreignKeys = new ArrayList<>();
    PersistentProperty identity = entity.getIdentity();
    if (identity != null) {
      PersistentEntityUtils.traversePersistentProperties(Collections.emptyList(), identity,
          (associations, property) -> {
            String name = name(naming.mappedName(associations, property), entity);
            primaryKey.add(name);
            columns.add(idColumn(entity, identity, property, name, associations));
          });
    }
    PersistentProperty version = version(entity);
    if (version != null && !version.isGenerated()) {
      columns.add(column(version, name(naming.mappedName(Collections.emptyList(), version),
          entity), true));
    }
    for (PersistentProperty property : entity.getPersistentProperties()) {
      // To-one associations become their target's id column(s); one-to-many and many-to-many
      // are skipped here (join tables, or the other side's column).
      PersistentEntityUtils.traversePersistentProperties(Collections.emptyList(), property,
          (associations, leaf) -> {
            SchemaModel.Column column = column(leaf, name(naming.mappedName(associations, leaf),
                entity), required(associations, leaf));
            if (column != null) {
              columns.add(column);
              Association foreign = foreignAssociation(associations);
              if (foreign != null && associations.size() == 1) {
                PersistentEntity target = foreign.getAssociatedEntity();
                String targetTable = name(target.getPersistedName(), target);
                foreignKeys.add(new SchemaModel.ForeignKey(SchemaModel.boundedName("fk_"
                    + plain(table) + "_" + plain(column.name())), List.of(column.name()),
                    targetTable, List.of(name(target.getNamingStrategy().mappedName(
                        Collections.emptyList(), leaf), target))));
              }
            }
          });
    }
    return new SchemaModel.Table(table, columns,
        primaryKey.isEmpty() ? null : new SchemaModel.PrimaryKey(primaryKey), List.of(),
        foreignKeys);
  }

  /** An id column: identity, sequence or database UUID, as Micronaut Data generates it. */
  private SchemaModel.Column idColumn(PersistentEntity entity, PersistentProperty identity,
                                      PersistentProperty property, String name,
                                      List<Association> associations) {
    SchemaModel.Column plain = column(property, name, true);
    if (plain == null || !identity.isGenerated() || foreignAssociation(associations) != null) {
      return plain;
    }
    GeneratedValue.Type type = identity.getAnnotationMetadata()
        .enumValue(GeneratedValue.class, GeneratedValue.Type.class)
        .orElse(GeneratedValue.Type.AUTO);
    boolean uuid = property.getDataType() == DataType.UUID;
    if (type == GeneratedValue.Type.AUTO) {
      // Micronaut Data's rule: UUIDs from the database, sequences on Oracle, else identity.
      type = uuid ? GeneratedValue.Type.UUID : dialect.id().equals("oracle")
          ? GeneratedValue.Type.SEQUENCE : GeneratedValue.Type.IDENTITY;
    }
    if (type == GeneratedValue.Type.SEQUENCE) {
      String sequence = identity.getAnnotationMetadata().stringValue(GeneratedValue.class, "ref")
          .filter(ref -> !ref.isBlank())
          .orElseGet(() -> entity.getPersistedName() + "_seq");
      sequence = name(sequence, entity);
      sequences.putIfAbsent(sequence, new SchemaModel.Sequence(sequence, 1L, 1L));
      return plain;
    }
    if (type == GeneratedValue.Type.UUID) {
      String generated = uuidDefault();
      // On MySQL, Micronaut Data puts the new UUID into the insert itself: no default needed.
      if (generated == null && !List.of("mysql", "mariadb", "tidb").contains(dialect.id())) {
        Log.warn("{}.{}: Migrax knows no database default for a generated UUID on {}; the "
            + "column gets none.", entity.getSimpleName(), property.getName(), dialect.id());
      }
      return withDefault(plain, generated);
    }
    return new SchemaModel.Column(plain.name(), plain.sqlType(), false, plain.length(),
        plain.precision(), plain.scale(), null, false, true, null, plain.logicalType());
  }

  /** The database function Micronaut Data uses for a generated UUID, or null. */
  private String uuidDefault() {
    return switch (dialect.id()) {
      case "postgresql", "cockroachdb", "yugabytedb" -> "gen_random_uuid()";
      case "h2" -> "random_uuid()";
      case "sqlserver" -> "newid()";
      case "oracle" -> "sys_guid()";
      default -> null;
    };
  }

  /** MySQL (and MariaDB, TiDB, through Micronaut's MySQL dialect) and Oracle: text UUIDs. */
  private boolean textUuids() {
    return switch (dialect.id()) {
      case "mysql", "mariadb", "tidb", "oracle" -> true;
      default -> false;
    };
  }

  private static SchemaModel.Column withDefault(SchemaModel.Column c, String value) {
    return new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(), c.length(), c.precision(),
        c.scale(), value, c.unique(), c.identity(), c.sequenceName(), c.logicalType(),
        c.comment());
  }

  /**
   * The join tables the entity owns (one-to-many without {@code mappedBy}, many-to-many): the
   * owner's id column(s), then the other side's, named by {@code @JoinTable} or the naming
   * strategy. No foreign keys: Micronaut Data deletes an entity without its join table rows,
   * so a foreign key there would make the application's deletes fail.
   */
  private List<SchemaModel.Table> joinTables(PersistentEntity entity) {
    List<SchemaModel.Table> result = new ArrayList<>();
    NamingStrategy naming = entity.getNamingStrategy();
    for (Association association : joinTableAssociations(entity)) {
      PersistentEntity target = association.getAssociatedEntity();
      Optional<? extends Association> inverse = association.getInverseSide();
      boolean owner = inverse.isEmpty();
      AnnotationMetadata metadata = inverse.isPresent() ? inverse.get().getAnnotationMetadata()
          : association.getAnnotationMetadata();
      String table = name(metadata.stringValue(JOIN_TABLE, "name")
          .orElseGet(() -> naming.mappedName(association)), entity);
      List<SchemaModel.Column> columns = new ArrayList<>();
      for (PersistentEntity side : List.of(entity, target)) {
        boolean left = side == entity;
        List<String> written = joinColumns(metadata, left == owner);
        List<String> names = new ArrayList<>();
        List<PersistentProperty> parts = new ArrayList<>();
        PersistentEntityUtils.traversePersistentProperties(Collections.emptyList(),
            side.getIdentity(), (associations, property) -> {
              parts.add(property);
              names.add(naming.mappedJoinTableColumn(side, associations, property));
            });
        if (written.size() == names.size()) {
          names.clear();
          names.addAll(written);
        }
        for (int i = 0; i < parts.size(); i++) {
          columns.add(column(parts.get(i), name(names.get(i), entity), true));
        }
      }
      result.add(new SchemaModel.Table(table, columns, null, List.of(), List.of()));
    }
    return result;
  }

  /** {@code @JoinTable(joinColumns)} or {@code inverseJoinColumns} names, when given. */
  private static List<String> joinColumns(AnnotationMetadata metadata, boolean owner) {
    AnnotationValue<java.lang.annotation.Annotation> joinTable =
        metadata.getAnnotation(JOIN_TABLE);
    if (joinTable == null) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (AnnotationValue<java.lang.annotation.Annotation> column :
        joinTable.getAnnotations(owner ? "joinColumns" : "inverseJoinColumns")) {
      column.stringValue("name").ifPresent(names::add);
    }
    return names;
  }

  /** Associations kept in a join table, also inside embedded values. */
  private static List<Association> joinTableAssociations(PersistentEntity entity) {
    List<Association> result = new ArrayList<>();
    List<PersistentProperty> properties = new ArrayList<>();
    if (entity.getIdentity() != null) {
      properties.add(entity.getIdentity());
    }
    properties.addAll(entity.getPersistentProperties());
    for (PersistentProperty property : properties) {
      collectJoinTableAssociations(property, result);
    }
    return result;
  }

  private static void collectJoinTableAssociations(PersistentProperty property,
                                                   List<Association> result) {
    if (property instanceof Embedded embedded) {
      for (PersistentProperty part : embedded.getAssociatedEntity().getPersistentProperties()) {
        collectJoinTableAssociations(part, result);
      }
    } else if (property instanceof Association association && association.isForeignKey()
        && association.getAnnotationMetadata().stringValue(Relation.class, "mappedBy").isEmpty()
        && association.getAnnotationMetadata().getAnnotation(JOIN_COLUMNS) == null) {
      result.add(association);
    }
  }

  /** The version property; Micronaut Data 5 throws where 4 returns null. */
  private static PersistentProperty version(PersistentEntity entity) {
    try {
      return entity.getVersion();
    } catch (IllegalStateException e) {
      return null;
    }
  }

  /** The first association that is not an embedded value: the reference to another entity. */
  private static Association foreignAssociation(List<Association> associations) {
    for (Association association : associations) {
      if (association.getKind() != Relation.Kind.EMBEDDED) {
        return association;
      }
    }
    return null;
  }

  /** Micronaut Data's rule: NOT NULL unless the property or a containing value is nullable. */
  private static boolean required(List<Association> associations, PersistentProperty property) {
    for (Association association : associations) {
      if (!association.isRequired()) {
        return false;
      }
    }
    Association foreign = foreignAssociation(associations);
    return foreign != null ? foreign.isRequired() : property.isRequired();
  }

  /** A column for a property, or null (with a warning) for a type Migrax doesn't write. */
  private SchemaModel.Column column(PersistentProperty property, String name, boolean required) {
    AnnotationMetadata metadata = property.getAnnotationMetadata();
    String definition = metadata.stringValue(MAPPED_PROPERTY, "definition")
        .filter(value -> !value.isBlank()).orElse(null);
    if (definition != null) {
      // The definition as written, as Micronaut Data writes it: NOT NULL only if it says so.
      return new SchemaModel.Column(name, definition, true, null, null, null, null, false,
          false, null, definition);
    }
    DataType type = property.getDataType();
    String logical = switch (type) {
      case STRING, CHARACTER -> "varchar";
      case UUID -> "uuid";
      case BOOLEAN -> "boolean";
      case TIMESTAMP -> "timestamp";
      case DATE -> "date";
      case TIME -> "time";
      case LONG -> "bigint";
      case INTEGER, SHORT, BYTE -> "integer";
      case BIGDECIMAL -> "decimal";
      case FLOAT -> "float";
      case DOUBLE -> "double";
      case BYTE_ARRAY -> "blob";
      case JSON -> "json";
      default -> null;
    };
    if (logical == null) {
      Log.warn("{}.{}: Migrax doesn't write columns of type {} (arrays, or types without a "
          + "@MappedProperty(definition)); the column is left out.",
          property.getOwner().getSimpleName(), property.getName(), type);
      return null;
    }
    Integer length = null;
    if (type == DataType.UUID && textUuids()) {
      // Micronaut Data stores UUIDs as text there: VARCHAR(36).
      return new SchemaModel.Column(name, "varchar", !required, 36, null, null, null, false,
          false, null, "varchar");
    }
    if (type == DataType.CHARACTER) {
      length = 1;
    } else if (type == DataType.STRING) {
      int max = metadata.findAnnotation("jakarta.validation.constraints.Size$List")
          .flatMap(list -> list.getAnnotations("value").stream().findFirst())
          .map(size -> size.intValue("max").orElse(255))
          .orElseGet(() -> persistenceColumn(metadata, "length").orElse(255));
      length = max == 255 ? null : max;
    }
    Integer precision = type == DataType.BIGDECIMAL
        ? persistenceColumn(metadata, "precision").orElse(null) : null;
    Integer scale = type == DataType.BIGDECIMAL
        ? persistenceColumn(metadata, "scale").orElse(null) : null;
    if (type == DataType.BYTE_ARRAY && dialect instanceof org.migrax.dialect.PostgresDialect) {
      // Micronaut Data writes bytes, which PostgreSQL keeps in bytea, not in a large object.
      return new SchemaModel.Column(name, "bytea", !required, null, null, null, null, false,
          false, null, "blob");
    }
    return new SchemaModel.Column(name, logical, !required, length, precision, scale, null,
        false, false, null, logical);
  }

  /** A member of JPA's {@code @Column}, which Micronaut Data reads for sizes. */
  private static Optional<Integer> persistenceColumn(AnnotationMetadata metadata,
                                                     String member) {
    for (String annotation : PERSISTENCE_COLUMNS) {
      java.util.OptionalInt value = metadata.intValue(annotation, member);
      if (value.isPresent() && value.getAsInt() > 0) {
        return Optional.of(value.getAsInt());
      }
    }
    return Optional.empty();
  }

  /**
   * A name as Migrax keeps it. Micronaut Data quotes names unless the entity says
   * {@code escape = false}; on databases that turn unquoted names into lower case (PostgreSQL),
   * a quoted name with capitals keeps them, so it becomes an exact name there. Unlike Spring
   * Data JDBC, it needs no exact names on H2 and Oracle: on H2 Micronaut Data quotes with
   * backticks, which H2 compares without letter case, and on Oracle it writes quoted names in
   * upper case, as unquoted names become.
   */
  private String name(String name, PersistentEntity entity) {
    boolean escaped = entity.getAnnotationMetadata().booleanValue(MAPPED_ENTITY, "escape")
        .orElse(true);
    if (escaped && dialect.unquotedNames() == Dialect.NameCase.LOWER
        && !name.equals(name.toLowerCase(Locale.ROOT))) {
      return "\"" + name + "\"";
    }
    return dialect.unquotedNames() == Dialect.NameCase.KEPT ? name
        : name.toLowerCase(Locale.ROOT);
  }

  /** A name for building constraint names: lower case, no quotes. */
  private static String plain(String name) {
    return SchemaModel.bareName(name).toLowerCase(Locale.ROOT);
  }
}
