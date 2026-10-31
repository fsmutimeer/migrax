package org.migrax.cli.renames.before;

import jakarta.persistence.*;

@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(length = 100)
  public String name;
}
