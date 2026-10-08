package io.migrax.ops;

/**
 * Renames a table, keeping its data.
 *
 * @param from current table name
 * @param to new table name
 * @since 0.1.0
 */
public record RenameTable(String from, String to) implements Operation {
  @Override
  public String kind() {
    return "rename_table";
  }
}
