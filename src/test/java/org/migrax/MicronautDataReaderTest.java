package org.migrax;

import org.migrax.dialect.Dialects;
import org.migrax.model.ModelExtractor;
import org.migrax.model.NamingStrategy;
import org.migrax.model.SchemaModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Micronaut Data's compiled mapping of the test entities, read without a database. */
class MicronautDataReaderTest {
  private static ModelExtractor.Result read(String dialect, ModelExtractor.Mode mode)
      throws Exception {
    return ModelExtractor.extract(Thread.currentThread().getContextClassLoader(),
        "org.migrax.micronautdata.fixtures", NamingStrategy.MICRONAUT, Dialects.byName(dialect),
        Map.of(), mode);
  }

  private static List<String> columns(SchemaModel.Table table) {
    return table.columns().stream().map(SchemaModel.Column::name).toList();
  }

  @Test
  void readsTablesColumnsAndRelations() throws Exception {
    // No JPA entities in the package: found without --extractor.
    ModelExtractor.Result result = read("h2", ModelExtractor.Mode.AUTO);
    assertTrue(result.source().startsWith("Micronaut Data"), result.source());
    SchemaModel model = result.model();
    assertEquals(List.of("author", "book_tags", "books", "page", "publisher", "tag"),
        model.tables().stream().map(SchemaModel.Table::name).toList());

    SchemaModel.Table books = model.table("books");
    assertEquals(List.of("id", "version", "title", "isbn_code", "summary", "price", "published",
        "cover", "author_id", "dims_width", "dims_height"), columns(books));
    assertTrue(books.column("id").identity(), "a plain @GeneratedValue is an identity column");
    assertFalse(books.column("title").nullable(), "not @Nullable: NOT NULL");
    assertTrue(books.column("price").nullable());
    assertEquals("TEXT", books.column("summary").sqlType(), "the definition as written");
    assertTrue(books.column("summary").nullable(), "a definition says NOT NULL itself");
    assertEquals(List.of(new SchemaModel.ForeignKey("fk_books_author_id", List.of("author_id"),
        "author", List.of("id"))), books.foreignKeys());

    // Many-to-many: a join table without foreign keys (deletes don't clear it).
    SchemaModel.Table bookTags = model.table("book_tags");
    assertEquals(List.of("book_id", "tag_id"), columns(bookTags));
    assertTrue(bookTags.foreignKeys().isEmpty());
    assertEquals("uuid", bookTags.column("tag_id").logicalType());

    assertEquals(new SchemaModel.Sequence("author_seq", 1L, 1L), model.sequence("author_seq"));
    assertFalse(model.table("author").column("id").identity());
    assertEquals("random_uuid()", model.table("tag").column("id").defaultValue());
    assertNotNull(model.table("page").column("book_id"));
  }

  @Test
  void followsTheDatabase() throws Exception {
    // Oracle: plain generated ids come from <table>_seq, as Micronaut Data does there.
    SchemaModel oracle = read("oracle", ModelExtractor.Mode.MICRONAUT_DATA).model();
    assertNotNull(oracle.sequence("books_seq"), oracle.sequences().toString());
    assertFalse(oracle.table("books").column("id").identity());

    // PostgreSQL: Micronaut Data quotes names, so capitals stay; bytes are bytea.
    SchemaModel postgres = read("postgresql", ModelExtractor.Mode.MICRONAUT_DATA).model();
    SchemaModel.Table publisher = postgres.table("\"Publisher\"");
    assertNotNull(publisher, postgres.tables().toString());
    assertNotNull(publisher.column("\"Name\""));
    assertEquals("bytea", postgres.table("books").column("cover").sqlType());
    assertEquals("gen_random_uuid()", postgres.table("tag").column("id").defaultValue());
    assertNull(postgres.table("publisher"));

    // MySQL and Oracle: Micronaut Data keeps UUIDs as VARCHAR(36); MySQL needs no default.
    for (String dialect : List.of("mysql", "oracle")) {
      SchemaModel model = read(dialect, ModelExtractor.Mode.MICRONAUT_DATA).model();
      SchemaModel.Column id = model.table("tag").column("id");
      assertEquals("varchar", id.logicalType(), dialect);
      assertEquals(36, id.length(), dialect);
      assertEquals(dialect.equals("oracle") ? "sys_guid()" : null, id.defaultValue(), dialect);
      assertEquals(36, model.table("book_tags").column("tag_id").length(), dialect);
    }
  }
}
