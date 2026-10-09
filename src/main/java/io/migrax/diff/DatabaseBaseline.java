package io.migrax.diff;

import io.migrax.dialect.Dialect;
import io.migrax.model.SchemaModel;
import io.migrax.plugin.DatabaseSchemaReader;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * How the first {@code generate} of a project, which has no snapshot yet, compares the entities
 * with the live database. When the database already matches the entities this must find no
 * changes at all; the real-database tests check exactly that on every supported engine.
 *
 * @since 0.2.0
 */
public final class DatabaseBaseline {
  private DatabaseBaseline() {}

  /**
   * The database schema, prepared for comparison with {@code entities}: the entities' sequences
   * that already exist are included, and on databases that fold unquoted names, names that
   * differ only in letter case are spelled as in the entities.
   *
   * @param schema the schema to read, or null for the connection's default
   */
  public static SchemaModel read(Connection connection, String schema, SchemaModel entities)
      throws SQLException {
    SchemaModel database = DatabaseSchemaReader.read(connection, schema)
        .withSequencesFrom(entities, DatabaseSchemaReader.sequenceNames(connection));
    if (DatabaseSchemaReader.foldsNames(connection)) {
      database = database.withNameCaseFrom(entities);
    }
    return withSequenceDefaults(database, entities);
  }

  /**
   * Ids that take their value from the entity's own sequence. Migrax writes them with a
   * {@code DEFAULT nextval('customer_seq')}, which PostgreSQL's driver reports as an
   * auto-increment column; they are not identity columns. Columns whose default uses any other
   * sequence (such as a legacy {@code serial} column) keep the identity flag the database
   * reports.
   */
  private static SchemaModel withSequenceDefaults(SchemaModel database, SchemaModel entities) {
    java.util.List<SchemaModel.Table> tables = new java.util.ArrayList<>();
    for (SchemaModel.Table table : database.tables()) {
      SchemaModel.Table entity = entities.table(table.name());
      java.util.List<SchemaModel.Column> columns = table.columns().stream().map(c -> {
        SchemaModel.Column wanted = entity == null ? null : entity.column(c.name());
        boolean ownSequence = c.identity() && c.sequenceName() != null && wanted != null
            && !wanted.identity()
            && (c.sequenceName().equalsIgnoreCase(wanted.sequenceName())
                || entities.sequences().stream()
                    .anyMatch(s -> s.name().equalsIgnoreCase(c.sequenceName())));
        return !ownSequence ? c : new SchemaModel.Column(c.name(), c.sqlType(), c.nullable(),
            c.length(), c.precision(), c.scale(), c.defaultValue(), c.unique(), false,
            wanted.sequenceName(), c.logicalType());
      }).toList();
      tables.add(new SchemaModel.Table(table.name(), columns, table.primaryKey(), table.indexes(),
          table.foreignKeys()));
    }
    return new SchemaModel(tables, database.sequences());
  }

  /**
   * The entities with the constraint names and column types of {@code previous} (the snapshot or
   * the database) wherever they mean the same thing, so only real differences remain.
   */
  public static SchemaModel align(SchemaModel entities, SchemaModel previous, Dialect dialect) {
    return entities.withConstraintNamesFrom(previous)
        .withCompatibleTypesFrom(previous, (existing, wanted) -> sameType(dialect, existing, wanted));
  }

  /** True when the dialect writes the same SQL type for both columns. */
  private static boolean sameType(Dialect dialect, SchemaModel.Column existing,
                                  SchemaModel.Column wanted) {
    // A UUID column stays as it is, whether stored natively or as binary(16): Hibernate reads
    // both, and converting it would rewrite every value.
    try {
      if ("uuid".equals(existing.logicalType()) && ("uuid".equals(wanted.logicalType())
          || List.of("binary(16)", "raw(16)", "uniqueidentifier", "uuid")
              .contains(normalize(dialect.columnType(wanted))))) {
        return true;
      }
      return normalize(dialect.columnType(existing)).equals(normalize(dialect.columnType(wanted)));
    } catch (RuntimeException e) {
      return false; // a type this dialect can't write: let the normal comparison decide
    }
  }

  /** SQL type text with spacing, case and standard synonyms (numeric = decimal) evened out. */
  private static String normalize(String type) {
    return type.toLowerCase(java.util.Locale.ROOT).replace(" ", "")
        .replace("numeric", "decimal").replace("charactervarying", "varchar");
  }
}
