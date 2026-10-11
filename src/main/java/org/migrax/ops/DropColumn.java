package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record DropColumn(String table, String column) implements Operation { public String kind(){return "drop_column";} }
