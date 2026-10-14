package org.migrax.ops;
/**
 * Drops a sequence.
 *
 * @param name the sequence name
 * @param sequence the sequence, when known (needed for one that is a row in a table)
 */
public record DropSequence(String name, org.migrax.model.SchemaModel.Sequence sequence)
    implements Operation {
  public DropSequence(String name) {
    this(name, null);
  }

  public String kind() {
    return "drop_sequence";
  }

  public boolean destructive() {
    return true;
  }
}
