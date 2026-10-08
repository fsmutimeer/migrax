package io.migrax.hibernate.fixtures;

import jakarta.persistence.*;
import java.math.BigDecimal;

/** Single-table inheritance root: Hibernate needs a DTYPE discriminator column. */
@Entity
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
public abstract class Payment {
  @Id
  @GeneratedValue
  public Long id;

  @Column(nullable = false)
  public BigDecimal amount;
}
