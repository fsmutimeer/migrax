package io.migrax.hibernate.fixtures;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/** Required many-to-one, many-to-many join table, named sequence generator. */
@Entity
public class PurchaseOrder {
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "order_ids")
  @SequenceGenerator(name = "order_ids", sequenceName = "purchaseOrderSeq", allocationSize = 20)
  public Long id;

  @ManyToOne(optional = false)
  public Customer customer;

  @ManyToMany
  public Set<Tag> tags = new HashSet<>();

  public LocalDateTime placedAt;

  @Lob
  public String notes;
}
