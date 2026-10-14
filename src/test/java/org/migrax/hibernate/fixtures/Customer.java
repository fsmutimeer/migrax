package org.migrax.hibernate.fixtures;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Typical Spring Boot entity: AUTO id, camelCase fields, unique column, embedded value, a check
 * constraint on the table and one on a column, comments.
 */
@Entity
@org.hibernate.annotations.Comment("People who buy from the shop")
@org.hibernate.annotations.Check(name = "ck_customer_balance", constraints = "balance >= 0")
public class Customer {
  public enum Status { ACTIVE, BLOCKED }

  public enum Tier { FREE, PRO }

  @Id
  @GeneratedValue
  public Long id;

  @Version
  public long version;

  @Column(nullable = false, length = 120)
  @org.hibernate.annotations.Comment("The customer's name as they write it")
  public String fullName;

  @Column(unique = true)
  @org.hibernate.annotations.Check(constraints = "email <> ''")
  public String email;

  public Instant createdAt;

  public LocalDate birthDate;

  public Status status;

  @Enumerated(EnumType.STRING)
  public Tier tier;

  @Column(precision = 10, scale = 2)
  public BigDecimal balance;

  public boolean marketingConsent;

  public UUID publicId;

  public byte[] avatar;

  @Lob
  public byte[] contract;

  @Embedded
  public Address homeAddress;

  @Embedded
  @AttributeOverrides({
      @AttributeOverride(name = "street", column = @Column(name = "billing_street")),
      @AttributeOverride(name = "city", column = @Column(name = "billing_city"))
  })
  public Address billingAddress;

  /** A Set of basic values with a NOT NULL element: Hibernate gives it a primary key. */
  @ElementCollection
  @CollectionTable(name = "customer_phones", joinColumns = @JoinColumn(name = "customer_id"))
  @Column(name = "phone", length = 30, nullable = false)
  public java.util.Set<String> phoneNumbers = new java.util.HashSet<>();

  /** An ordered List: primary key over the join and order columns. */
  @ElementCollection
  @OrderColumn
  public java.util.List<Integer> scores = new java.util.ArrayList<>();

  /** A Set of embeddables with nullable columns: no primary key. */
  @ElementCollection
  public java.util.Set<Address> previousAddresses = new java.util.HashSet<>();
}
