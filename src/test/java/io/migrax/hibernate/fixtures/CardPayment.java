package io.migrax.hibernate.fixtures;

import jakarta.persistence.Entity;

@Entity
public class CardPayment extends Payment {
  public String cardLastDigits;
}
