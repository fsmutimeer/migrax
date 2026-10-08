package com.matrix;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_lines")
public class OrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "line_seq")
    @SequenceGenerator(name = "line_seq", sequenceName = "order_line_seq", allocationSize = 20)
    public Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "order_id")
    public PurchaseOrder order;

    @Column(nullable = false)
    public int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    public BigDecimal unitPrice;
}
