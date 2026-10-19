package org.migrax.micronaut.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** The entity of the billing database (persistence unit jpa.billing). */
@Entity
public class Invoice {
  @Id
  public Long id;

  @Column(length = 40)
  public String owner;
}
