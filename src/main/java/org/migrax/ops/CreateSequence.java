package org.migrax.ops;
import org.migrax.model.SchemaModel;
public record CreateSequence(SchemaModel.Sequence sequence) implements Operation { public String kind(){return "create_sequence";} }
