package org.migrax.cli.renames.many;

import jakarta.persistence.*;

/** The name field gone, and four new columns it may have been renamed to. */
@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(length = 100)
  public String city;

  @Column(length = 100)
  public String country;

  @Column(length = 100)
  public String region;

  @Column(length = 100)
  public String street;
}
