package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record AddIndex(String table, SchemaModel.Index index) implements Operation { public String kind(){return "add_index";} }
