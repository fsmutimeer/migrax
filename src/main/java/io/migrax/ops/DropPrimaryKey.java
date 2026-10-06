package io.migrax.ops;
public record DropPrimaryKey(String table, String constraintName) implements Operation {
  public DropPrimaryKey(String table) {
    this(table, null);
  }

  public String kind() {
    return "drop_primary_key";
  }
}
