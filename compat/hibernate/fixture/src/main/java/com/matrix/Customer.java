package com.matrix;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "customers", indexes = @Index(name = "idx_customers_city", columnList = "city"))
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 150)
    public String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    public Status status;

    public Instant createdAt;
    public LocalDate birthday;

    @Column(precision = 12, scale = 2)
    public BigDecimal balance;

    public boolean active;

    @Column(length = 2000)
    public String notes;

    @Embedded
    public Address address;

    @Version
    public long version;

    @ElementCollection
    @CollectionTable(name = "customer_phones", joinColumns = @JoinColumn(name = "customer_id"))
    @Column(name = "phone", length = 30)
    public Set<String> phoneNumbers = new HashSet<>();

    @ElementCollection
    @OrderColumn
    public java.util.List<Integer> scores = new java.util.ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "customer_addresses")
    public Set<Address> previousAddresses = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "customer_tags",
        joinColumns = @JoinColumn(name = "customer_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id"))
    public Set<Tag> tags = new HashSet<>();
}
