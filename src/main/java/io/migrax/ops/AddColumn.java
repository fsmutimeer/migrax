package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record AddColumn(String table, SchemaModel.Column column) implements Operation { public String kind(){return "add_column";} }
