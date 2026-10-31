package org.migrax.cli.renames.twodropped;

import jakarta.persistence.*;

/** Before: two columns that may both have become full_name. */
@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(length = 100)
  public String name;

  @Column(length = 100)
  public String nickname;
}
