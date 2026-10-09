package io.migrax;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.migrax.dialect.Dialect;
import io.migrax.dialect.MySqlDialect;
import io.migrax.dialect.PostgresDialect;
import io.migrax.diff.DiffEngine;
import io.migrax.model.SchemaModel;
import io.migrax.ops.AlterColumn;
import io.migrax.ops.Operation;
import io.migrax.plugin.DatabaseSchemaReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The first generate compares the entities with the live database. These cases once produced
 * changes that were not real (MySQL on Windows, 2026-10-09).
 */
class BaselineComparisonTest {

  private static SchemaModel.Column column(String name, String logical) {
    return new SchemaModel.Column(name, logical, true, null, null, null, null, false, false, null,
        logical);
  }

  private static SchemaModel.Table table(String name, SchemaModel.Column... columns) {
    return new SchemaModel.Table(name, List.of(columns), null, List.of(), List.of());
  }

  /** The same steps generate takes when the database is the baseline. */
  private static List<Operation> diff(SchemaModel database, SchemaModel entities, Dialect dialect,
                                      boolean foldsNames) {
    SchemaModel previous = foldsNames ? database.withNameCaseFrom(entities) : database;
    SchemaModel current = entities.withConstraintNamesFrom(previous).withCompatibleTypesFrom(
        previous, (a, b) -> dialect.columnType(a).equalsIgnoreCase(dialect.columnType(b)));
    return new DiffEngine().diff(previous, current);
  }

  @Test
  void tableNamesThatDifferOnlyInCaseAreTheSameTableWhenTheDatabaseFoldsNames() {
    SchemaModel database = new SchemaModel(List.of(table("categories_seq", column("next_val", "bigint"))));
    SchemaModel entities = new SchemaModel(List.of(table("categories_SEQ", column("next_val", "bigint"))));

    assertEquals(List.of(), diff(database, entities, new MySqlDialect(), true));
    // Where names are case-sensitive they really are two tables.
    assertEquals(2, diff(database, entities, new MySqlDialect(), false).size());
  }

  @Test
  void columnNamesThatDifferOnlyInCaseMatchToo() {
    SchemaModel database = new SchemaModel(List.of(table("orders", column("placedat", "timestamp"))));
    SchemaModel entities = new SchemaModel(List.of(table("orders", column("placedAt", "timestamp"))));
    assertEquals(List.of(), diff(database, entities, new PostgresDialect(), true));
  }

  @Test
  void typesTheDatabaseWritesTheSameWayAreNotChanged() {
    // An Instant field is "timestamp with time zone"; MySQL stores both kinds as datetime(6).
    SchemaModel database = new SchemaModel(List.of(table("orders", column("placedAt", "timestamp"))));
    SchemaModel entities = new SchemaModel(List.of(table("orders", column("placedAt", "timestamptz"))));

    assertEquals(List.of(), diff(database, entities, new MySqlDialect(), true));
    // PostgreSQL has a separate type, so there it is a real change.
    List<Operation> postgres = diff(database, entities, new PostgresDialect(), true);
    assertEquals(1, postgres.size());
    assertTrue(postgres.get(0) instanceof AlterColumn, postgres.toString());
  }

  @Test
  void aSequenceStoredAsANextValTableIsTheEntitySequence() {
    // MySQL has no sequences: Hibernate keeps categories_SEQ in a table with one next_val column.
    SchemaModel database = new SchemaModel(List.of(
        table("categories", column("id", "bigint")), table("categories_seq", column("next_val", "bigint"))));
    SchemaModel.Sequence sequence = new SchemaModel.Sequence("categories_SEQ", 1L, 50L);
    SchemaModel entities = new SchemaModel(List.of(table("categories", column("id", "bigint"))),
        List.of(sequence));

    SchemaModel baseline = database.withSequencesFrom(entities, java.util.Set.of());
    assertEquals(List.of(), diff(baseline, entities, new MySqlDialect(), true));
  }

  @Test
  void onlyTheEntitysSequencesAreTakenFromTheDatabase() {
    SchemaModel.Sequence sequence = new SchemaModel.Sequence("orders_seq", 1L, 50L);
    SchemaModel entities = new SchemaModel(List.of(), List.of(sequence));
    // orders_id_seq belongs to an identity column: it must never show up, or it would be dropped.
    SchemaModel baseline = SchemaModel.empty()
        .withSequencesFrom(entities, java.util.Set.of("orders_seq", "orders_id_seq"));
    assertEquals(List.of(sequence), baseline.sequences());
    assertEquals(List.of(), diff(baseline, entities, new PostgresDialect(), true));
  }

  @Test
  void sequenceNamesAreListedFromTheDatabase() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:sequences")) {
      connection.createStatement().execute("CREATE SEQUENCE orders_seq START WITH 1 INCREMENT BY 50");
      assertTrue(DatabaseSchemaReader.sequenceNames(connection).contains("orders_seq"));
    }
  }

  @Test
  void indexesNamedLikeTheirForeignKeyAreNotReportedAsIndexes() throws Exception {
    try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:fk_index")) {
      var statement = connection.createStatement();
      statement.execute("CREATE TABLE parent (id INT PRIMARY KEY)");
      statement.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id INT, note VARCHAR(20))");
      // What MySQL does by itself for a foreign key: an index with the key's name.
      statement.execute("CREATE INDEX fk_child_parent ON child (parent_id)");
      statement.execute("ALTER TABLE child ADD CONSTRAINT fk_child_parent "
          + "FOREIGN KEY (parent_id) REFERENCES parent (id)");
      statement.execute("CREATE INDEX idx_child_note ON child (note)");

      SchemaModel.Table child = DatabaseSchemaReader.read(connection).table("child");
      assertEquals(List.of("fk_child_parent"),
          child.foreignKeys().stream().map(SchemaModel.ForeignKey::name).toList());
      assertEquals(List.of("idx_child_note"),
          child.indexes().stream().map(SchemaModel.Index::name).toList(),
          "an index the user created stays; the foreign key's own index is left out");
    }
  }
}
