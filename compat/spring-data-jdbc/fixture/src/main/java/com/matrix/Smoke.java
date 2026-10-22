package com.matrix;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jdbc.core.mapping.AggregateReference;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Spring Data JDBC itself saves, loads, updates and deletes every kind of aggregate against the
 * migrated database: the proof that the schema Migrax wrote matches Spring Data JDBC's mapping.
 * Arguments: JDBC URL, user, password.
 */
@SpringBootApplication
@EnableJdbcRepositories(considerNestedRepositories = true)
public class Smoke {
  interface Customers extends ListCrudRepository<Customer, Long> {}

  interface Regions extends ListCrudRepository<Region, Long> {}

  interface Products extends ListCrudRepository<Product, String> {}

  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(Smoke.class);
    app.setDefaultProperties(Map.of("spring.datasource.url", args[0],
        "spring.datasource.username", args[1],
        "spring.datasource.password", args.length > 2 ? args[2] : ""));
    try (ConfigurableApplicationContext context = app.run()) {
      Regions regions = context.getBean(Regions.class);
      Customers customers = context.getBean(Customers.class);
      Products products = context.getBean(Products.class);

      Region region = new Region();
      region.code = "EU";
      region = regions.save(region);

      Customer customer = new Customer();
      customer.name = "Ann";
      customer.email = "ann@example.com";
      customer.status = Status.ACTIVE;
      customer.balance = new BigDecimal("12.50");
      customer.createdAt = LocalDateTime.of(2026, 10, 22, 12, 0);
      customer.vip = true;
      customer.externalId = UUID.randomUUID();
      customer.photo = new byte[] {1, 2, 3};
      customer.home = new Address("Main Street 1", "Rome");
      OrderLine line = new OrderLine();
      line.product = "pen";
      line.amount = BigDecimal.ONE;
      line.discounts.add(new Discount("WELCOME"));
      customer.lines.add(line);
      customer.tags.add(new Tag("gold"));
      customer.notes.put("first",
          new Note("hello", java.util.List.of(new NoteLine("a"), new NoteLine("b"))));
      customer.profile = new Profile("likes pens");
      customer.region = AggregateReference.to(region.id);
      customer = customers.save(customer);

      Product product = new Product();
      product.sku = "P-1";
      product.title = "Pen";
      products.save(product);

      Customer back = customers.findById(customer.id).orElseThrow();
      check(back.email.equals("ann@example.com"), "email");
      check(back.status == Status.ACTIVE && back.vip, "status and vip");
      check(back.balance.compareTo(new BigDecimal("12.50")) == 0, "balance");
      check(back.photo.length == 3, "photo");
      check(back.home.city().equals("Rome"), "embedded address");
      check(back.lines.size() == 1 && back.lines.get(0).discounts.size() == 1, "order lines");
      check(back.tags.size() == 1 && back.notes.get("first").text().equals("hello")
          && back.notes.get("first").lines().size() == 2,
          "tags and notes");
      check(back.profile.bio().equals("likes pens"), "profile");
      check(back.region.getId().equals(region.id), "region reference");
      check(products.findById("P-1").isPresent(), "product");
      // Updates and deletes go through the version column and the child tables.
      back.lines.clear();
      customers.save(back);
      customers.deleteById(back.id);
      products.deleteById("P-1");
      System.out.println("SMOKE OK");
    }
  }

  private static void check(boolean ok, String what) {
    if (!ok) {
      throw new IllegalStateException("Spring Data JDBC read back the wrong " + what);
    }
  }
}
