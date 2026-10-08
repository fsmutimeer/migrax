package io.migrax.model;

import java.util.Locale;

/**
 * How table and column names are derived from entities, mirroring Hibernate's naming setup so
 * the generated schema matches what the application queries.
 *
 * <ul>
 *   <li>{@link #SPRING}: Spring Boot's default. Join tables are named
 *       {@code <owner table>_<attribute>} and every name is converted to snake_case, e.g.
 *       {@code fullName} becomes {@code full_name}.</li>
 *   <li>{@link #JPA}: Hibernate's own default (Quarkus, Helidon, Jakarta EE, plain Hibernate).
 *       Join tables are named {@code <owner table>_<target table>} and names are used as
 *       written.</li>
 *   <li>{@link #JPA_SNAKE}: JPA join-table names with snake_case names.</li>
 *   <li>{@link #MICRONAUT}: Micronaut Data's default. JPA join-table names, snake_case by
 *       Micronaut's own rule ({@code myURL} becomes {@code my_url}, not {@code myurl}), and
 *       Hibernate's legacy sequence names ({@code hibernate_sequence}).</li>
 * </ul>
 *
 * @param springJoinTables use Spring's {@code <owner table>_<attribute>} join-table names
 * @param snakeCase convert names to snake_case
 * @param micronaut use Micronaut Data's snake_case rule and legacy sequence names
 * @since 0.1.0
 */
public record NamingStrategy(boolean springJoinTables, boolean snakeCase, boolean micronaut) {
  public static final NamingStrategy SPRING = new NamingStrategy(true, true);
  public static final NamingStrategy JPA = new NamingStrategy(false, false);
  public static final NamingStrategy JPA_SNAKE = new NamingStrategy(false, true);
  public static final NamingStrategy MICRONAUT = new NamingStrategy(false, true, true);

  public NamingStrategy(boolean springJoinTables, boolean snakeCase) {
    this(springJoinTables, snakeCase, false);
  }

  /** Applies the physical naming rule to a table, column or sequence name. */
  public String physical(String name) {
    if (name == null || !snakeCase) {
      return name;
    }
    return micronaut ? micronautSnakeCase(name) : snakeCase(name);
  }

  /**
   * Hibernate's {@code CamelCaseToUnderscoresNamingStrategy}: dots become underscores, an
   * underscore is inserted between a lowercase letter, an uppercase letter and a lowercase
   * letter, and the result is lowercased. {@code fullName} becomes {@code full_name};
   * {@code myURL} becomes {@code myurl}.
   */
  public static String snakeCase(String name) {
    StringBuilder builder = new StringBuilder(name.replace('.', '_'));
    for (int i = 1; i < builder.length() - 1; i++) {
      if (Character.isLowerCase(builder.charAt(i - 1))
          && Character.isUpperCase(builder.charAt(i))
          && Character.isLowerCase(builder.charAt(i + 1))) {
        builder.insert(i++, '_');
      }
    }
    return builder.toString().toLowerCase(Locale.ROOT);
  }

  /**
   * Micronaut Data's {@code NamingStrategies.UnderScoreSeparatedLowerCase}, a port of
   * {@code NameUtils.underscoreSeparate(name).toLowerCase(Locale.ENGLISH)}: an underscore goes
   * before every uppercase letter that does not follow another uppercase letter, so
   * {@code myURL} becomes {@code my_url}, {@code productSKU} becomes {@code product_sku} and
   * {@code line1Address} becomes {@code line1_address}.
   */
  public static String micronautSnakeCase(String name) {
    String input = name.replace('-', '_');
    StringBuilder out = new StringBuilder(input.length() + 4);
    boolean first = true;
    char last = '0';
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (first) {
        if (c != '_') {
          out.append(c);
        }
        first = false;
      } else if (Character.isUpperCase(c) && !Character.isUpperCase(last)) {
        out.append('_').append(c);
      } else {
        if (c == '.') {
          first = true;
        }
        if (c != '_') {
          if (last == '_') {
            out.append('_');
          }
          out.append(c);
        }
      }
      last = c;
    }
    return out.toString().toLowerCase(Locale.ENGLISH);
  }

  /** Parses {@code spring}, {@code jpa}, {@code jpa-snake} or {@code micronaut}. */
  public static NamingStrategy parse(String value) {
    return switch (value.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
      case "spring", "snake", "spring-boot" -> SPRING;
      case "jpa", "hibernate", "standard", "quarkus", "helidon", "jakarta" -> JPA;
      case "jpa-snake", "hibernate-snake", "quarkus-snake" -> JPA_SNAKE;
      case "micronaut", "micronaut-data" -> MICRONAUT;
      default -> throw new IllegalArgumentException("Unknown naming strategy '" + value
          + "'. Use spring, jpa, jpa-snake or micronaut.");
    };
  }

  /** Short name accepted by {@link #parse(String)}. */
  public String id() {
    if (equals(SPRING)) {
      return "spring";
    }
    if (equals(JPA)) {
      return "jpa";
    }
    if (equals(JPA_SNAKE)) {
      return "jpa-snake";
    }
    if (equals(MICRONAUT)) {
      return "micronaut";
    }
    return "spring-join-tables, names as written";
  }
}
