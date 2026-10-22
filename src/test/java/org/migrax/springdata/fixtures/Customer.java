package org.migrax.springdata.fixtures;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/** An aggregate root with every kind of property Spring Data JDBC maps. */
@Table("customers")
public class Customer {
  public enum Status { ACTIVE, BLOCKED }

  @Id Long id;
  String name;
  @Column("mail") String email;
  @Column("Nick") String nick;
  Status status;
  BigDecimal balance;
  LocalDateTime createdAt;
  boolean vip;
  UUID externalId;
  byte[] photo;
  @Version Long version;
  @Transient String scratch;
  @Embedded.Nullable(prefix = "home_") Address home;
  @MappedCollection(idColumn = "customer_id", keyColumn = "position") List<Order> orders;
  Set<Tag> tags;
  Map<String, Note> notes;
  Profile profile;
  AggregateReference<Region, Long> region;

  public record Address(String street, String city) {}

  public static class Order {
    @Id Long id;
    String product;
  }

  public record Tag(String label) {}

  /** A map value without an id, with its own list. */
  public record Note(String text, List<NoteLine> lines) {}

  public record NoteLine(String words) {}

  /** A one-to-one child with @Table and a collection of its own: still part of Customer. */
  @Table("profile")
  public record Profile(String bio, Set<Hobby> hobbies) {}

  public record Hobby(String name) {}
}
