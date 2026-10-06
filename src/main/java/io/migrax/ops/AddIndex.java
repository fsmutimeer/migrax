package io.migrax.ops;
import io.migrax.model.SchemaModel;
public record AddIndex(String table, SchemaModel.Index index) implements Operation { public String kind(){return "add_index";} }
