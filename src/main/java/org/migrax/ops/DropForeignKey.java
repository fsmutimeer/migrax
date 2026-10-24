package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record DropForeignKey(String table, String name) implements Operation { public String kind(){return "drop_foreign_key";} }
