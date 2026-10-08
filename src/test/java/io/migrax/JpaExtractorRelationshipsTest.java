package io.migrax;

import io.migrax.model.JpaExtractor;
import io.migrax.model.SchemaModel;
import io.migrax.diff.DiffEngine;
import io.migrax.dialect.H2Dialect;
import io.migrax.ops.AddForeignKey;
import io.migrax.runner.MigrationRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JpaExtractorRelationshipsTest {
  @TempDir
  Path temporary;

  @Test
  void extractsExplicitJoinTablesAndDoesNotDuplicateInverseManyToMany() throws Exception {
    SchemaModel schema = new JpaExtractor().extract("io.migrax.model.fixtures");

    SchemaModel.Table manyToMany = schema.table("owner_target_link");
    assertNotNull(manyToMany);
    assertEquals(2, manyToMany.foreignKeys().size());
    assertEquals("owner_key", manyToMany.foreignKeys().get(0).columns().get(0));
    assertEquals("mapping_owner", manyToMany.foreignKeys().get(0).referencedTable());
    assertEquals("target_key", manyToMany.foreignKeys().get(1).columns().get(0));
    assertEquals("mapping_target", manyToMany.foreignKeys().get(1).referencedTable());
    assertNotNull(schema.table("owner_child_link"));
    assertEquals(1, schema.tables().stream()
        .filter(table -> "owner_target_link".equals(table.name()))
        .count());
    assertEquals(5, new DiffEngine().diff(SchemaModel.empty(), schema).stream()
        .filter(AddForeignKey.class::isInstance)
        .count());
    SchemaModel.Table child = schema.table("mapping_child");
    assertEquals("owner_ref", child.column("owner_ref").name());
    assertEquals("mapping_owner", child.foreignKeys().get(0).referencedTable());
  }

  @Test
  void rejectsRelationshipToCompositeIdInsteadOfEmittingAnIncorrectForeignKey() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> new JpaExtractor().extract("io.migrax.model.compositefixtures"));

    assertTrue(error.getMessage().contains("composite and @EmbeddedId relationship keys"));
  }

  @Test
  void reportsClassLoadingFailuresInsteadOfSilentlySkippingClasses() {
    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    ClassLoader failingLoader = new ClassLoader(original) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals("io.migrax.model.fixtures.MappingOwner")) {
          throw new NoClassDefFoundError("simulated missing application dependency");
        }
        return super.loadClass(name, resolve);
      }
    };
    thread.setContextClassLoader(failingLoader);
    try {
      IllegalStateException error = assertThrows(
          IllegalStateException.class,
          () -> new JpaExtractor().extract("io.migrax.model.fixtures"));

      assertTrue(error.getMessage().contains("io.migrax.model.fixtures.MappingOwner"));
      assertTrue(error.getCause() instanceof LinkageError);
    } finally {
      thread.setContextClassLoader(original);
    }
  }

  @Test
  void appliesGeneratedJoinTableAndForeignKeyDdlOnH2() throws Exception {
    SchemaModel schema = new JpaExtractor().extract("io.migrax.model.fixtures");
    var dialect = new H2Dialect();
    Path migration = temporary.resolve("0001_relationships.sql");
    String sql = new DiffEngine().diff(SchemaModel.empty(), schema).stream()
        .map(dialect::render)
        .map(statement -> statement + ";")
        .collect(java.util.stream.Collectors.joining("\n"));
    Files.writeString(migration, sql);

    try (var connection = DriverManager.getConnection(
        "jdbc:h2:mem:relationship-ddl;DB_CLOSE_DELAY=-1")) {
      new MigrationRunner().migrate(connection, java.util.List.of(migration));
      try (var keys = connection.getMetaData().getImportedKeys(
          connection.getCatalog(), "PUBLIC", "OWNER_TARGET_LINK")) {
        int count = 0;
        while (keys.next()) {
          count++;
        }
        assertEquals(2, count);
      }
      try (var keys = connection.getMetaData().getImportedKeys(
          connection.getCatalog(), "PUBLIC", "MAPPING_CHILD")) {
        assertTrue(keys.next());
        assertEquals("MAPPING_OWNER", keys.getString("PKTABLE_NAME"));
        assertEquals("OWNER_REF", keys.getString("FKCOLUMN_NAME"));
      }
    }
  }
}
