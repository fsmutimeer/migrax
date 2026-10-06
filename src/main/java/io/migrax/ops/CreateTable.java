package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record CreateTable(SchemaModel.Table table) implements Operation { public String kind(){return "create_table";} }
