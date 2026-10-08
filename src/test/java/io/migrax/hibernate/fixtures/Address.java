package io.migrax.hibernate.fixtures;

import jakarta.persistence.Embeddable;

@Embeddable
public class Address {
  public String street;
  public String city;
}
