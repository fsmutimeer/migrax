package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record DropForeignKey(String table, String name) implements Operation { public String kind(){return "drop_foreign_key";} }
