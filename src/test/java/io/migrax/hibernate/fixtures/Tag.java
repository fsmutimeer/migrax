package io.migrax.hibernate.fixtures;

import jakarta.persistence.*;

@Entity
public class Tag {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true, length = 40)
  public String label;
}
