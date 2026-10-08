package io.migrax.ops;

/**
 * Drops a named single-column unique constraint. Removing uniqueness keeps all data, so this is
 * not a destructive operation.
 *
 * @param table table name
 * @param column column name
 * @param name constraint name
 * @since 0.1.0
 */
public record DropUnique(String table, String column, String name) implements Operation {
  @Override
  public String kind() {
    return "drop_unique";
  }
}
