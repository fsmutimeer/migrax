package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record DropIndex(String table, String name) implements Operation { public String kind(){return "drop_index";} }
