package com.matrix;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * OpenJPA against the schema Migrax migrated: saves every kind of entity (identity, table
 * sequence and native sequence ids, inheritance, element collections, a join table) and reads
 * them back. Fails on any mismatch between the schema and OpenJPA's mapping.
 *
 * <p>Arguments: JDBC URL, then optionally user, password and driver class (H2 by default).
 */
public class Smoke {
  public static void main(String[] args) {
    EntityManagerFactory factory = Persistence.createEntityManagerFactory("fixture", Map.of(
        "jakarta.persistence.jdbc.driver", args.length > 3 ? args[3] : "org.h2.Driver",
        "jakarta.persistence.jdbc.url", args[0],
        "jakarta.persistence.jdbc.user", args.length > 1 ? args[1] : "sa",
        "jakarta.persistence.jdbc.password", args.length > 2 ? args[2] : "",
        "openjpa.Log", "DefaultLevel=WARN"));
    EntityManager em = factory.createEntityManager();
    em.getTransaction().begin();
    Tag tag = new Tag();
    tag.label = "regular";
    em.persist(tag);
    Address address = new Address();
    address.street = "Main 1";
    address.city = "Lahore";
    Customer customer = new Customer();
    customer.email = "a@example.com";
    customer.status = Status.ACTIVE;
    customer.createdAt = Instant.now();
    customer.birthday = LocalDate.of(1990, 1, 2);
    customer.balance = new BigDecimal("12.50");
    customer.active = true;
    customer.notes = "first";
    customer.address = address;
    customer.phoneNumbers.add("555");
    customer.scores.add(7);
    customer.previousAddresses.add(address);
    customer.tags.add(tag);
    em.persist(customer);
    PurchaseOrder order = new PurchaseOrder();
    order.customer = customer;
    order.placedAt = LocalDateTime.now();
    OrderLine line = new OrderLine();
    line.order = order;
    line.quantity = 2;
    line.unitPrice = new BigDecimal("3.25");
    order.lines.add(line);
    em.persist(order);
    CardPayment card = new CardPayment();
    card.amount = new BigDecimal("6.50");
    card.last4 = "4242";
    em.persist(card);
    CashPayment cash = new CashPayment();
    cash.amount = new BigDecimal("1.00");
    cash.receivedBy = "desk";
    em.persist(cash);
    em.getTransaction().commit();
    em.clear();

    // OpenJPA loads collections through its enhanced code, not on direct field reads from
    // outside the entity, so they are read back with queries over the same mapping.
    check(em.createQuery("select c.email from Customer c", String.class).getSingleResult()
        .equals("a@example.com"), "customer");
    check(em.createQuery("select t.label from Customer c join c.tags t", String.class)
        .getSingleResult().equals("regular"), "tags");
    check(em.createQuery("select s from Customer c join c.scores s", Integer.class)
        .getSingleResult() == 7, "scores");
    check(em.createQuery("select p from Customer c join c.phoneNumbers p", String.class)
        .getSingleResult().equals("555"), "phone numbers");
    check(em.createQuery("select a.city from Customer c join c.previousAddresses a",
        String.class).getSingleResult().equals("Lahore"), "previous addresses");
    check(em.createQuery("select l.quantity from PurchaseOrder o join o.lines l", Integer.class)
        .getSingleResult() == 2, "order and its lines");
    check(em.createQuery("select count(p) from Payment p", Long.class).getSingleResult() == 2,
        "payments");
    em.close();
    factory.close();
    System.out.println("SMOKE OK");
  }

  private static void check(boolean condition, String what) {
    if (!condition) {
      throw new IllegalStateException("OpenJPA read back the wrong " + what);
    }
  }
}
