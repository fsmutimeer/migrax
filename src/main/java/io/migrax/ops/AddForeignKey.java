package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record AddForeignKey(String table, SchemaModel.ForeignKey fk) implements Operation { public String kind(){return "add_foreign_key";} }
