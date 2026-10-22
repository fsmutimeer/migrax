package com.matrix;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.ListCrudRepository;

/**
 * Starts a JPA application with the Migrax starter: Hibernate validates the schema, so the
 * start only succeeds when the migrations ran before it. A second database (billing,
 * {@code migrax.databases.billing}) is migrated too. Then JPA saves a row, and the migrax health
 * component reports both databases. The health classes differ between Spring Boot 3 and 4, so
 * they are read by reflection. Argument: the folder for the two H2 databases.
 */
@SpringBootApplication
@EnableJpaRepositories(considerNestedRepositories = true)
public class Smoke {
  private static String folder;

  @Entity
  @Table(name = "book")
  public static class Book {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String title;
  }

  interface Books extends ListCrudRepository<Book, Long> {}

  @Bean
  @Primary
  DataSource dataSource() {
    return h2("shop");
  }

  @Bean
  DataSource billing() {
    return h2("billing");
  }

  private static DataSource h2(String name) {
    JdbcDataSource source = new JdbcDataSource();
    source.setURL("jdbc:h2:file:" + folder + "/" + name);
    source.setUser("sa");
    return source;
  }

  public static void main(String[] args) throws Exception {
    folder = args[0];
    SpringApplication app = new SpringApplication(Smoke.class);
    app.setDefaultProperties(Map.of(
        "migrax.databases.billing.locations", "classpath:db/billing"));
    try (ConfigurableApplicationContext context = app.run()) {
      Book book = new Book();
      book.title = "Saved by JPA";
      context.getBean(Books.class).save(book);
      // One component per database: default and billing.
      Object composite = context.getBean("migraxHealthIndicator");
      StringBuilder health = new StringBuilder();
      for (String database : new String[] {"default", "billing"}) {
        Object indicator = call(composite, "getContributor", database);
        Object result = call(indicator, "health");
        health.append(' ').append(database).append('=').append(call(result, "getStatus"))
            .append(call(result, "getDetails"));
      }
      System.out.println("STARTER OK books=" + context.getBean(Books.class).count()
          + " health:" + health);
    }
  }

  /** Calls a public method; the classes behind the health interfaces aren't public. */
  private static Object call(Object target, String name, Object... arguments) throws Exception {
    for (java.lang.reflect.Method method : target.getClass().getMethods()) {
      if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
        method.setAccessible(true);
        return method.invoke(target, arguments);
      }
    }
    throw new NoSuchMethodException(name);
  }
}
