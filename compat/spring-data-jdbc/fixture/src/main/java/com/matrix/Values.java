package com.matrix;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;

/** The other types of the fixture: values, child entities and two more aggregate roots. */
final class Values {
  private Values() {}
}

enum Status { ACTIVE, BLOCKED }

record Address(String street, String city) {}

/** A child entity with its own id and its own children. */
class OrderLine {
  @Id public Long id;
  public String product;
  public BigDecimal amount;
  public Set<Discount> discounts = new HashSet<>();
}

record Discount(String code) {}

record Tag(String label) {}

/** A map value without an id that has its own list: its rows carry the parent's map key. */
record Note(String text, java.util.List<NoteLine> lines) {}

record NoteLine(String words) {}

record Profile(String bio) {}

/** An aggregate that others refer to by id only. */
class Region {
  @Id public Long id;
  public String code;
}

/** No annotation on the class: found through its repository. The application sets the id. */
class Product implements Persistable<String> {
  @Id public String sku;
  public String title;
  @Transient public boolean fresh = true;

  @Override
  public String getId() {
    return sku;
  }

  @Override
  public boolean isNew() {
    return fresh;
  }
}
