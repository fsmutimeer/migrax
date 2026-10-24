package org.migrax.ops;
/**
 * Drops a foreign key.
 *
 * @param table the table that has the key
 * @param name the key name
 * @param renamed true when the same key (same columns and referenced columns) is added again
 *     under a new name in the same migration, as when renaming a table changes the generated
 *     key name. That loses nothing, so it is not destructive (since 0.17.1).
 */
public record DropForeignKey(String table, String name, boolean renamed) implements Operation {
  public DropForeignKey(String table, String name) {
    this(table, name, false);
  }

  public String kind() {
    return "drop_foreign_key";
  }

  public boolean destructive() {
    return !renamed;
  }
}
