package org.migrax.micronautdata.fixtures;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.data.annotation.GeneratedValue;
import io.micronaut.data.annotation.Id;
import io.micronaut.data.annotation.MappedEntity;
import io.micronaut.data.annotation.MappedProperty;
import io.micronaut.data.annotation.Relation;
import io.micronaut.data.annotation.Version;
import io.micronaut.data.annotation.sql.JoinTable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Micronaut Data entities with every kind of property and relation, for the reader test. */
@MappedEntity("books")
public record Book(@Id @GeneratedValue Long id, String title,
                   @MappedProperty("isbn_code") String isbn,
                   @MappedProperty(definition = "TEXT") String summary,
                   @Nullable BigDecimal price, @Version Long version, boolean published,
                   @Nullable byte[] cover,
                   @Relation(Relation.Kind.MANY_TO_ONE) Author author,
                   @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "book")
                   List<Page> pages,
                   @Relation(Relation.Kind.EMBEDDED) Dimensions dims,
                   @Relation(Relation.Kind.MANY_TO_MANY) @JoinTable(name = "book_tags")
                   Set<Tag> tags) {}
