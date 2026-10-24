package org.migrax.ops;

import org.migrax.model.SchemaModel;

/**
 * Gives a foreign key a new name: the same columns and referenced columns, as when renaming a
 * table renames the key Hibernate generates. Databases that can rename a key do so in place;
 * the others drop it and add it again under the new name. Not destructive (since 0.17.1).
 *
 * @param table the table that has the key
 * @param from the key's current name
 * @param key the key under its new name
 */
public record RenameForeignKey(String table, String from, SchemaModel.ForeignKey key)
    implements Operation {
  public String kind() {
    return "rename_foreign_key";
  }
}
