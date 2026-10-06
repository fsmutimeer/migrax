package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record AlterColumn(String table, SchemaModel.Column before, SchemaModel.Column after) implements Operation { public String kind(){return "alter_column";} }
