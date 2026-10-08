package io.migrax.ops;

/**
 * Adds a named single-column unique constraint, used when an existing column becomes unique.
 *
 * @param table table name
 * @param column column name
 * @param name constraint name
 * @since 0.1.0
 */
public record AddUnique(String table, String column, String name) implements Operation {
  @Override
  public String kind() {
    return "add_unique";
  }
}
