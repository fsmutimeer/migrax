package org.migrax.junit.fixture;

import jakarta.persistence.*;

@Entity
public class Book {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, length = 100)
  public String title;
}
