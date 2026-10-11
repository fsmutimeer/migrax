package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record AddForeignKey(String table, SchemaModel.ForeignKey fk) implements Operation { public String kind(){return "add_foreign_key";} }
