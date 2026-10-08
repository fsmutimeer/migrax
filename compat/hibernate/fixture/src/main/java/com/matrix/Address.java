package com.matrix;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class Address {
    @Column(length = 120)
    public String street;
    public String city;
}
