package org.migrax.ops;

/**
 * Drops a check constraint. Removing a check keeps all data, so this is not a destructive
 * operation.
 *
 * @param table table name
 * @param name constraint name
 * @since 0.5.0
 */
public record DropCheck(String table, String name) implements Operation {
  @Override
  public String kind() {
    return "drop_check";
  }
}
