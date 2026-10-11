package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record AddColumn(String table, SchemaModel.Column column) implements Operation { public String kind(){return "add_column";} }
