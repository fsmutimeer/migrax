package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record AddPrimaryKey(String table, SchemaModel.PrimaryKey key) implements Operation { public String kind(){return "add_primary_key";} }
