package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record RenameColumn(String table, String from, String to) implements Operation { public String kind(){return "rename_column";} }
