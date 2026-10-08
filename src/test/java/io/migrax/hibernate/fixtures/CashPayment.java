package io.migrax.hibernate.fixtures;

import jakarta.persistence.Entity;

@Entity
public class CashPayment extends Payment {
  public String receivedBy;
}
