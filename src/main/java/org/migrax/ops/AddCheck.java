package org.migrax.ops;

import org.migrax.model.SchemaModel;

/**
 * Adds a check constraint to an existing table.
 *
 * @param table table name
 * @param check the constraint
 * @since 0.5.0
 */
public record AddCheck(String table, SchemaModel.Check check) implements Operation {
  @Override
  public String kind() {
    return "add_check";
  }
}
