package com.matrix;

import io.micronaut.context.ApplicationContext;
import io.micronaut.data.annotation.Join;
import io.micronaut.data.jdbc.annotation.JdbcRepository;
import io.micronaut.data.model.query.builder.sql.Dialect;
import io.micronaut.data.repository.CrudRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Micronaut Data itself saves, loads, updates and deletes every kind of entity against the
 * migrated database: the proof that the schema Migrax wrote matches Micronaut Data's mapping.
 * Arguments: JDBC URL, user, password.
 */
public class Smoke {
  @JdbcRepository(dialect = Dialect.MN_DIALECT)
  public interface Books extends CrudRepository<Book, Long> {
    @Join("author")
    @Join(value = "tags", type = Join.Type.LEFT_FETCH)
    Optional<Book> findById(Long id);
  }

  @JdbcRepository(dialect = Dialect.MN_DIALECT)
  public interface Authors extends CrudRepository<Author, Long> {}

  @JdbcRepository(dialect = Dialect.MN_DIALECT)
  public interface Pages extends CrudRepository<Page, Long> {
    List<Page> findByBookId(Long id);
  }

  @JdbcRepository(dialect = Dialect.MN_DIALECT)
  public interface Tags extends CrudRepository<Tag, UUID> {}

  @JdbcRepository(dialect = Dialect.MN_DIALECT)
  public interface Publishers extends CrudRepository<Publisher, Long> {}

  public static void main(String[] args) {
    try (ApplicationContext context = ApplicationContext.run(Map.of(
        "datasources.default.url", args[0], "datasources.default.username", args[1],
        "datasources.default.password", args.length > 2 ? args[2] : "",
        "datasources.default.dialect", "MN_DIALECT"))) {
      Authors authors = context.getBean(Authors.class);
      Books books = context.getBean(Books.class);
      Pages pages = context.getBean(Pages.class);
      Tags tags = context.getBean(Tags.class);
      Publishers publishers = context.getBean(Publishers.class);

      Author author = authors.save(new Author(null, "Ann"));
      Tag tag = tags.save(new Tag(null, "gold"));
      check(tag.id() != null, "tag id from the database");
      Book book = new Book();
      book.setTitle("Migrations");
      book.setIsbn("978-1");
      book.setPrice(new BigDecimal("12.50"));
      book.setGenre(Book.Genre.NOVEL);
      book.setPublished(true);
      book.setRef(UUID.randomUUID());
      book.setCover(new byte[] {1, 2, 3});
      book.setAuthor(author);
      book.setDims(new Dimensions(10, 20));
      book.getTags().add(tag);
      book = books.save(book);
      pages.save(new Page(null, 1, book));
      Publisher publisher = publishers.save(new Publisher(null, "Acme"));

      Book back = books.findById(book.getId()).orElseThrow();
      check(back.getIsbn().equals("978-1"), "isbn");
      check(back.getPrice().compareTo(new BigDecimal("12.50")) == 0, "price");
      check(back.getGenre() == Book.Genre.NOVEL && back.isPublished(), "genre and published");
      check(back.getCover().length == 3, "cover");
      check(back.getCreatedAt() != null, "created date");
      check(back.getAuthor().fullName().equals("Ann"), "author");
      check(back.getDims().height() == 20, "embedded dimensions");
      check(back.getTags().size() == 1, "tags (join table)");
      check(pages.findByBookId(book.getId()).size() == 1, "pages");
      check(publishers.findById(publisher.id()).orElseThrow().name().equals("Acme"),
          "publisher");
      // Updates go through the version column; deletes through the join table.
      back.setTitle("Migrations, 2nd edition");
      books.update(back);
      pages.deleteAll();
      back.getTags().clear();
      books.delete(books.findById(book.getId()).orElseThrow());
      publishers.deleteAll();
      System.out.println("SMOKE OK");
    }
  }

  private static void check(boolean ok, String what) {
    if (!ok) {
      throw new IllegalStateException("Micronaut Data read back the wrong " + what);
    }
  }
}
