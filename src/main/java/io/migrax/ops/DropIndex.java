package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record DropIndex(String table, String name) implements Operation { public String kind(){return "drop_index";} }
