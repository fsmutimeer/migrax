package com.matrix;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Embedded;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/** Aggregate root: written names, every value type, embedded, list, set, map, one-to-one. */
@Table("customers")
public class Customer {
  @Id public Long id;
  public String name;
  @Column("mail") public String email;
  public Status status;
  public BigDecimal balance;
  public LocalDateTime createdAt;
  public boolean vip;
  public UUID externalId;
  public byte[] photo;
  @Version public Long version;
  @Embedded.Nullable(prefix = "home_") public Address home;
  @MappedCollection(idColumn = "customer_id", keyColumn = "position")
  public List<OrderLine> lines = new ArrayList<>();
  public Set<Tag> tags = new HashSet<>();
  public Map<String, Note> notes = new HashMap<>();
  public Profile profile;
  public AggregateReference<Region, Long> region;
}
