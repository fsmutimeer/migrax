package org.migrax.cli.renames.onename;

import jakarta.persistence.*;

/** After: name and nickname gone, full_name new. */
@Entity
@Table(name = "customers")
public class Customer {
  @Id
  public Long id;

  @Column(length = 100)
  public String fullName;
}
