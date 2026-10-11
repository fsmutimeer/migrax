package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record AddPrimaryKey(String table, SchemaModel.PrimaryKey key) implements Operation { public String kind(){return "add_primary_key";} }
