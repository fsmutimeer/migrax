package io.migrax;

import io.migrax.dialect.H2Dialect;
import io.migrax.diff.DiffEngine;
import io.migrax.hibernate.fixtures.Address;
import io.migrax.hibernate.fixtures.CardPayment;
import io.migrax.hibernate.fixtures.CashPayment;
import io.migrax.hibernate.fixtures.Customer;
import io.migrax.hibernate.fixtures.Payment;
import io.migrax.hibernate.fixtures.PurchaseOrder;
import io.migrax.hibernate.fixtures.Tag;
import io.migrax.model.JpaExtractor;
import io.migrax.model.ModelExtractor;
import io.migrax.model.NamingStrategy;
import io.migrax.model.SchemaModel;
import io.migrax.runner.MigrationRunner;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.ImplicitJoinTableNameSource;
import org.hibernate.boot.model.naming.ImplicitNamingStrategyJpaCompliantImpl;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The real proof that generated migrations work: apply Migrax's SQL to a database, then start
 * Hibernate 6 with {@code hbm2ddl.auto=validate} (it fails on any missing table, column,
 * sequence or incompatible type) and save and load real rows.
 */
class HibernateValidationTest {
  private static final List<Class<?>> ENTITIES = List.of(
      Customer.class, PurchaseOrder.class, Tag.class, Payment.class, CardPayment.class,
      CashPayment.class);

  @TempDir
  Path migrations;

  /** Same rule as Spring Boot's SpringImplicitNamingStrategy (spring-boot is not a dependency). */
  public static final class SpringImplicitNamingStrategy
      extends ImplicitNamingStrategyJpaCompliantImpl {
    @Override
    public Identifier determineJoinTableName(ImplicitJoinTableNameSource source) {
      String name = source.getOwningPhysicalTableName() + "_"
          + source.getAssociationOwningAttributePath().getProperty();
      return toIdentifier(name, source.getBuildingContext());
    }
  }

  @Test
  void springBootNamingValidatesAndPersists() throws Exception {
    String url = "jdbc:h2:mem:hibernate_spring;DB_CLOSE_DELAY=-1";
    applyMigration(url, NamingStrategy.SPRING);
    try (SessionFactory factory = hibernate(url, true)) {
      persistAndQuery(factory);
    }
  }

  @Test
  void hibernateDefaultNamingValidatesAndPersists() throws Exception {
    String url = "jdbc:h2:mem:hibernate_jpa;DB_CLOSE_DELAY=-1";
    applyMigration(url, NamingStrategy.JPA);
    try (SessionFactory factory = hibernate(url, false)) {
      persistAndQuery(factory);
    }
  }

  /** Guards the test itself: a schema with the wrong naming must fail validation. */
  @Test
  void mismatchedNamingIsRejectedByHibernate() throws Exception {
    String url = "jdbc:h2:mem:hibernate_mismatch;DB_CLOSE_DELAY=-1";
    applyMigration(url, NamingStrategy.SPRING);
    org.junit.jupiter.api.Assertions.assertThrows(
        org.hibernate.tool.schema.spi.SchemaManagementException.class,
        () -> hibernate(url, false).close());
  }

  @Test
  void hibernateMetadataModeValidatesAndPersistsWithSpringNaming() throws Exception {
    String url = "jdbc:h2:mem:hibernate_meta_spring;DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url)) {
      generateAndApply(connection, NamingStrategy.SPRING, new H2Dialect(),
          migrations.resolve("0001_initial.sql"), ModelExtractor.Mode.HIBERNATE);
    }
    try (SessionFactory factory = hibernate(url, true)) {
      persistAndQuery(factory);
    }
  }

  @Test
  void hibernateMetadataModeValidatesAndPersistsWithJpaNaming() throws Exception {
    String url = "jdbc:h2:mem:hibernate_meta_jpa;DB_CLOSE_DELAY=-1";
    try (var connection = DriverManager.getConnection(url)) {
      generateAndApply(connection, NamingStrategy.JPA, new H2Dialect(),
          migrations.resolve("0001_initial.sql"), ModelExtractor.Mode.HIBERNATE);
    }
    try (SessionFactory factory = hibernate(url, false)) {
      persistAndQuery(factory);
    }
  }

  /** Both readers agree on the fixture schema once constraint names are aligned. */
  @Test
  void annotationAndHibernateModesAgreeOnH2() throws Exception {
    SchemaModel annotations = model(NamingStrategy.SPRING, new H2Dialect(),
        ModelExtractor.Mode.ANNOTATIONS);
    SchemaModel hibernate = model(NamingStrategy.SPRING, new H2Dialect(),
        ModelExtractor.Mode.HIBERNATE).withConstraintNamesFrom(annotations)
        .withCompatibleTypesFrom(annotations);
    var differences = new DiffEngine().diff(annotations, hibernate);
    assertEquals(List.of(), differences.stream().map(io.migrax.diff.Migrations::summary).toList());
  }

  private void applyMigration(String url, NamingStrategy naming) throws Exception {
    try (var connection = DriverManager.getConnection(url)) {
      generateAndApply(connection, naming, new H2Dialect(), migrations.resolve("0001_initial.sql"));
    }
  }

  static SchemaModel model(NamingStrategy naming, io.migrax.dialect.Dialect dialect,
                           ModelExtractor.Mode mode) throws Exception {
    return ModelExtractor.extract(Thread.currentThread().getContextClassLoader(),
        "io.migrax.hibernate.fixtures", naming, dialect, java.util.Map.of(), mode).model();
  }

  /** SQL that Migrax generates for the fixture entities. */
  static String generate(NamingStrategy naming, io.migrax.dialect.Dialect dialect,
                         ModelExtractor.Mode mode) throws Exception {
    return new DiffEngine().diff(SchemaModel.empty(), model(naming, dialect, mode)).stream()
        .map(dialect::render)
        .map(statement -> statement + ";")
        .collect(Collectors.joining("\n"));
  }

  static void generateAndApply(java.sql.Connection connection, NamingStrategy naming,
                               io.migrax.dialect.Dialect dialect, Path file) throws Exception {
    generateAndApply(connection, naming, dialect, file, ModelExtractor.Mode.ANNOTATIONS);
  }

  /** Writes the generated migration to {@code file} and applies it with the Migrax runner. */
  static void generateAndApply(java.sql.Connection connection, NamingStrategy naming,
                               io.migrax.dialect.Dialect dialect, Path file,
                               ModelExtractor.Mode mode) throws Exception {
    String sql = generate(naming, dialect, mode);
    Files.writeString(file, sql);
    try {
      new MigrationRunner().migrate(connection, List.of(file));
    } catch (Exception e) {
      throw new AssertionError("Generated " + dialect.id() + " migration failed:\n" + sql, e);
    }
  }

  private static SessionFactory hibernate(String url, boolean spring) {
    return hibernate(url, null, null, spring);
  }

  /** Starts Hibernate with schema validation; fails if the schema does not match. */
  static SessionFactory hibernate(String url, String user, String password, boolean spring) {
    return hibernate(url, user, password, spring, true);
  }

  /** Starts Hibernate, with or without its schema validation. */
  static SessionFactory hibernate(String url, String user, String password, boolean spring,
                                  boolean validate) {
    StandardServiceRegistryBuilder builder = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.connection.url", url)
        .applySetting("hibernate.hbm2ddl.auto", validate ? "validate" : "none")
        .applySetting("hibernate.show_sql", "false");
    if (user != null) {
      builder.applySetting("hibernate.connection.username", user)
          .applySetting("hibernate.connection.password", password);
    }
    if (spring) {
      builder.applySetting("hibernate.physical_naming_strategy",
              CamelCaseToUnderscoresNamingStrategy.class.getName())
          .applySetting("hibernate.implicit_naming_strategy",
              SpringImplicitNamingStrategy.class.getName());
    }
    StandardServiceRegistry registry = builder.build();
    try {
      MetadataSources sources = new MetadataSources(registry);
      ENTITIES.forEach(sources::addAnnotatedClass);
      return sources.buildMetadata().buildSessionFactory();
    } catch (RuntimeException e) {
      StandardServiceRegistryBuilder.destroy(registry);
      throw e;
    }
  }

  static void persistAndQuery(SessionFactory factory) {
    persistAndQuery(factory, false);
  }

  /**
   * Saves one of each entity and reads them back.
   *
   * @param oneTransactionEach save each entity in its own transaction. SQLite needs it: it has one
   *     writer, and Hibernate takes table-based ids on a second connection, which waits for the
   *     transaction that already inserted an identity row.
   */
  static void persistAndQuery(SessionFactory factory, boolean oneTransactionEach) {
    List<Object> entities = new java.util.ArrayList<>();
    {
      Customer customer = new Customer();
      customer.fullName = "Ada Lovelace";
      customer.email = "ada@example.com";
      customer.createdAt = Instant.now();
      customer.status = Customer.Status.ACTIVE;
      customer.tier = Customer.Tier.PRO;
      customer.balance = new BigDecimal("12.50");
      customer.publicId = UUID.randomUUID();
      customer.homeAddress = new Address();
      customer.homeAddress.street = "1 Main St";
      customer.homeAddress.city = "London";
      customer.billingAddress = new Address();
      customer.billingAddress.street = "2 Side St";
      customer.billingAddress.city = "Paris";
      entities.add(customer);

      Tag tag = new Tag();
      tag.label = "vip";
      entities.add(tag);

      PurchaseOrder order = new PurchaseOrder();
      order.customer = customer;
      order.placedAt = LocalDateTime.now();
      order.notes = "Leave at the door";
      order.tags.add(tag);
      entities.add(order);

      CardPayment card = new CardPayment();
      card.amount = new BigDecimal("12.50");
      card.cardLastDigits = "4242";
      entities.add(card);
      CashPayment cash = new CashPayment();
      cash.amount = BigDecimal.ONE;
      cash.receivedBy = "Bob";
      entities.add(cash);
    }
    if (oneTransactionEach) {
      entities.forEach(entity -> factory.inTransaction(session -> session.persist(entity)));
    } else {
      factory.inTransaction(session -> entities.forEach(session::persist));
    }

    factory.inSession(session -> {
      assertEquals(1L, session.createQuery("select count(c) from Customer c", Long.class)
          .getSingleResult());
      // A query for the value, not the entity: SQLite's driver can't read @Lob columns.
      assertEquals("Paris", session.createQuery("select c.billingAddress.city from Customer c",
          String.class).getSingleResult());
      assertEquals(1L, session.createQuery(
          "select count(o) from PurchaseOrder o join o.tags t where t.label = 'vip'", Long.class)
          .getSingleResult());
      assertEquals(1L, session.createQuery("select count(p) from CardPayment p", Long.class)
          .getSingleResult());
      assertEquals(2L, session.createQuery("select count(p) from Payment p", Long.class)
          .getSingleResult());
    });
  }
}
