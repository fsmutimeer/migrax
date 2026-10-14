package org.migrax.ops;

import org.migrax.model.SchemaModel;

/**
 * Sets or removes the comment of a table or a column.
 *
 * @param table table name
 * @param column the column as it is after the change, or null for the table comment
 * @param comment the new comment, or null to remove it
 * @since 0.5.0
 */
public record SetComment(String table, SchemaModel.Column column, String comment)
    implements Operation {
  @Override
  public String kind() {
    return "set_comment";
  }
}
