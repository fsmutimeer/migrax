package org.migrax.cli.renames.oddname;

import jakarta.persistence.*;

/** The name field renamed to a column whose name a shell would run part of. */
@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(name = "full$(id)", length = 100)
  public String fullName;
}
