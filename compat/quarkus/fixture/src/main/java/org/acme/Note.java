package org.acme;

import jakarta.persistence.*;

@Entity
@Table(name = "note")
public class Note {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
  @Column(nullable = false) public String body;
}
