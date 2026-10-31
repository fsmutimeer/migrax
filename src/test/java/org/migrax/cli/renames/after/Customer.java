package org.migrax.cli.renames.after;

import jakarta.persistence.*;

/** The name field renamed to fullName, and a new country column that comes first. */
@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(length = 2)
  public String country;

  @Column(length = 100)
  public String fullName;
}
