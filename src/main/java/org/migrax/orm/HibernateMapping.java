package org.migrax.orm;

import org.migrax.model.SchemaModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What every Hibernate reader shares: JDBC type codes as Migrax's logical types, readable names
 * for Hibernate's hashed constraint names, and reflective calls for methods that differ between
 * Hibernate versions. Uses no Hibernate class, so it loads with any of them.
 */
final class HibernateMapping {
  private static final Pattern GENERATED_NAME = Pattern.compile("(?i)(FK|UK|IDX)[0-9a-z]{15,}");

  private HibernateMapping() {}

  /** Whether Hibernate made the name up from a hash, as in {@code FKa1b2c3...}. */
  static boolean generatedName(String name) {
    return GENERATED_NAME.matcher(name).matches();
  }

  /** Readable Migrax-style name instead of Hibernate's hashed FK/UK/IDX names. */
  static String constraintName(String name, String readable) {
    if (name == null || name.isBlank() || generatedName(name)) {
      return SchemaModel.boundedName(readable.toLowerCase(Locale.ROOT));
    }
    return name;
  }

  /** A SQL type for comparing: lower case, single spaces, no space before parentheses. */
  static String normalize(String type) {
    return type.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
        .replace(" (", "(").replace(", ", ",").trim();
  }

  /** Migrax's logical type for a JDBC type code. */
  static String logicalType(int code) {
    return switch (code) {
      case 12, 1 -> "varchar";
      case -9, -15 -> "nvarchar";
      case -1, -16, 4001, 4002 -> "text";
      case 2005, 2011, 3005, 3006 -> "clob";
      case 4, 5, -6 -> "integer";
      case -5 -> "bigint";
      case 16, -7 -> "boolean";
      case 2, 3 -> "decimal";
      case 8 -> "double";
      case 6, 7 -> "float";
      case 91 -> "date";
      case 92, 2013 -> "time";
      case 93 -> "timestamp";
      case 2014, 3003 -> "timestamptz";
      case -2, -3, -4, 4003 -> "varbinary";
      case 2004, 3004 -> "blob";
      case 3000 -> "uuid";
      case 3001, 3018 -> "json";
      default -> "other";
    };
  }

  static String text(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /** The elements of a collection or an iterator; empty for null. */
  static List<Object> list(Object found) {
    List<Object> all = new ArrayList<>();
    if (found instanceof java.util.Collection<?> collection) {
      all.addAll(collection);
    } else if (found instanceof java.util.Iterator<?> iterator) {
      iterator.forEachRemaining(all::add);
    } else if (found != null) {
      all.add(found);
    }
    return all;
  }

  /**
   * Calls a public method whose parameters accept {@code args}; returns null when this
   * Hibernate version has no such method.
   */
  static Object call(Object target, String name, Object... args) {
    for (java.lang.reflect.Method method : target.getClass().getMethods()) {
      if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
        continue;
      }
      boolean fits = true;
      for (int i = 0; i < args.length; i++) {
        fits &= args[i] != null && method.getParameterTypes()[i].isInstance(args[i]);
      }
      if (fits) {
        try {
          return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
          if (e.getCause() instanceof RuntimeException runtime) {
            throw runtime;
          }
          throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
      }
    }
    return null;
  }
}
