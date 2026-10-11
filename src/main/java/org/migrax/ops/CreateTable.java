package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record CreateTable(SchemaModel.Table table) implements Operation { public String kind(){return "create_table";} }
