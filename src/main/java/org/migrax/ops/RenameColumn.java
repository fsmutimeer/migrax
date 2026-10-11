package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record RenameColumn(String table, String from, String to) implements Operation { public String kind(){return "rename_column";} }
