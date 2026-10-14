package com.matrix;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue
    public Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    public Customer customer;

    public LocalDateTime placedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    public List<OrderLine> lines = new ArrayList<>();
}
