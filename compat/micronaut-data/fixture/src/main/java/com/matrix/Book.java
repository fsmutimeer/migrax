package com.matrix;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.DateCreated;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.Version;
import io.micronaut.data.annotation.sql.JoinTable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Every kind of property and relation Micronaut Data maps. */
@MappedEntity("books")
public class Book {
  public enum Genre { NOVEL, POETRY }

  @Id @GeneratedValue private Long id;
  private String title;
  @MappedProperty("isbn_code") private String isbn;
  @Nullable private BigDecimal price;
  @DateCreated @Nullable private LocalDateTime createdAt;
  @Version private Long version;
  private Genre genre;
  private boolean published;
  @Nullable private UUID ref;
  @Nullable private byte[] cover;
  @Relation(Relation.Kind.MANY_TO_ONE) private Author author;
  @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "book")
  private List<Page> pages = new ArrayList<>();
  @Relation(Relation.Kind.EMBEDDED) private Dimensions dims;
  @Relation(value = Relation.Kind.MANY_TO_MANY, cascade = Relation.Cascade.PERSIST)
  @JoinTable(name = "book_tags")
  private Set<Tag> tags = new HashSet<>();

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getIsbn() { return isbn; }
  public void setIsbn(String isbn) { this.isbn = isbn; }
  public BigDecimal getPrice() { return price; }
  public void setPrice(BigDecimal price) { this.price = price; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public Long getVersion() { return version; }
  public void setVersion(Long version) { this.version = version; }
  public Genre getGenre() { return genre; }
  public void setGenre(Genre genre) { this.genre = genre; }
  public boolean isPublished() { return published; }
  public void setPublished(boolean published) { this.published = published; }
  public UUID getRef() { return ref; }
  public void setRef(UUID ref) { this.ref = ref; }
  public byte[] getCover() { return cover; }
  public void setCover(byte[] cover) { this.cover = cover; }
  public Author getAuthor() { return author; }
  public void setAuthor(Author author) { this.author = author; }
  public List<Page> getPages() { return pages; }
  public void setPages(List<Page> pages) { this.pages = pages; }
  public Dimensions getDims() { return dims; }
  public void setDims(Dimensions dims) { this.dims = dims; }
  public Set<Tag> getTags() { return tags; }
  public void setTags(Set<Tag> tags) { this.tags = tags; }
}
