package org.migrax.springdata.fixtures;

import org.springframework.data.annotation.Id;

/** An aggregate without annotations: found through its repository. */
public class Product {
  @Id String sku;
  String title;
}
